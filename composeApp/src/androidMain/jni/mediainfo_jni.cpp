// A deliberately tiny bridge onto MediaInfoLib (https://github.com/MediaArea/MediaInfoLib).
//
// MediaInfoLib already knows how to serialise everything it found as JSON, and Kotlin already
// has a JSON parser, so this exposes exactly one call and lets the parsing happen up in
// commonMain where it can be unit-tested. Nothing here needs to change when a new field is
// wanted from a file.
//
// Compiled by .github/workflows/build-mediainfo.yml, not by Gradle: MediaInfoLib and ZenLib
// are linked in statically, so the AAR that workflow produces carries one self-contained
// libmediainfo_jni.so per ABI and the app has no NDK dependency of its own.
//
// Strings cross the boundary as UTF-8 byte arrays rather than jstring. JNI's GetStringUTFChars
// speaks modified UTF-8, which disagrees with real UTF-8 over supplementary characters, and a
// track title with an emoji in it would otherwise be a crash rather than a mojibake.

#include <jni.h>

#include <string>

#include <MediaInfo/MediaInfo.h>
#include <ZenLib/Ztring.h>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nuvio_app_features_downloads_MediaInfoNative_nativeInformJson(
        JNIEnv* env,
        jobject /* thiz */,
        jbyteArray filePathUtf8) {
    if (filePathUtf8 == nullptr) {
        return nullptr;
    }

    const jsize pathLength = env->GetArrayLength(filePathUtf8);
    std::string pathUtf8(static_cast<size_t>(pathLength), '\0');
    if (pathLength > 0) {
        env->GetByteArrayRegion(
                filePathUtf8, 0, pathLength, reinterpret_cast<jbyte*>(&pathUtf8[0]));
    }
    if (pathUtf8.empty()) {
        return nullptr;
    }

    ZenLib::Ztring path;
    path.From_UTF8(pathUtf8.c_str());

    MediaInfoLib::MediaInfo handle;
    handle.Option(__T("CharSet"), __T("UTF-8"));
    handle.Option(__T("Output"), __T("JSON"));

    // Open returns the number of files opened, so zero is the failure case: an unreadable path,
    // or a container MediaInfoLib declines. The caller falls back to MediaExtractor.
    if (handle.Open(path) == 0) {
        handle.Close();
        return nullptr;
    }

    const std::string json = ZenLib::Ztring(handle.Inform()).To_UTF8();
    handle.Close();

    if (json.empty()) {
        return nullptr;
    }

    const jsize jsonLength = static_cast<jsize>(json.size());
    jbyteArray result = env->NewByteArray(jsonLength);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
            result, 0, jsonLength, reinterpret_cast<const jbyte*>(json.data()));
    return result;
}
