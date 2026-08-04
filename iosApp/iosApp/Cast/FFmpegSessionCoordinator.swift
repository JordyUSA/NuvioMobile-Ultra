import Foundation
import ffmpegkit

/// Decides who owns the single in-flight FFmpegKit session.
///
/// `CastTranscoder.cancel()` calls `FFmpegKit.cancel()`, which cancels *every* session, not its
/// own. That was defensible while casting was the only thing using FFmpegKit — the comment there
/// says as much — but the video converter breaks that assumption: cancelling a cast would kill a
/// running conversion, and cancelling a conversion would kill a cast.
///
/// Rather than reach for `FFmpegSession`'s id accessor, which this project has already found to be
/// unreliable to reference from Swift across otherwise-identical builds, the invariant is restored
/// instead: only one owner runs a session at a time, and a cancel is ignored unless it comes from
/// the owner that actually holds it. Global cancellation is then correct again, because there is
/// only ever one session to cancel.
///
/// Casting outranks converting. A cast is something the user is waiting on right now; a conversion
/// is background work that can be restarted.
final class FFmpegSessionCoordinator {

    enum Owner {
        case cast
        case converter
    }

    static let shared = FFmpegSessionCoordinator()

    private let lock = NSLock()
    private var owner: Owner?

    private init() {}

    /// Claims the session. Returns false when a higher-priority owner already holds it, in which
    /// case the caller must not start an FFmpeg session.
    @discardableResult
    func begin(_ candidate: Owner) -> Bool {
        lock.lock()
        defer { lock.unlock() }

        switch (owner, candidate) {
        case (nil, _):
            owner = candidate
            return true
        case (.some(let current), _) where current == candidate:
            return true
        case (.converter, .cast):
            // Casting pre-empts a conversion. The conversion's completion callback will report a
            // cancellation and the repository re-queues it.
            FFmpegKit.cancel()
            owner = .cast
            return true
        default:
            return false
        }
    }

    func end(_ candidate: Owner) {
        lock.lock()
        defer { lock.unlock() }
        if owner == candidate { owner = nil }
    }

    /// Cancels the running session, but only when [candidate] is the one that started it.
    func cancel(_ candidate: Owner) {
        lock.lock()
        let holds = owner == candidate
        if holds { owner = nil }
        lock.unlock()

        if holds { FFmpegKit.cancel() }
    }

    var isBusy: Bool {
        lock.lock()
        defer { lock.unlock() }
        return owner != nil
    }
}
