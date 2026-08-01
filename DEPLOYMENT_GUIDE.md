# Deployment Guide: iOS IPA + GitHub Actions

## Workflow

Your Machine (Windows/Mac) → Push to branch → GitHub Actions (builds IPA) → Download artifact → Sideload → Test on iPhone

## Getting Started

1. Push code to `feature/chromecast-hw-transcoding` branch
2. Watch Actions tab
3. When build completes (green checkmark), download IPA
4. Use AltStore or Sideloadly to sideload

## Build Time

- First build: 30-45 minutes
- Subsequent builds: 20-30 minutes
- Cached dependencies speed it up

## Files

- `.github/workflows/build-ipa.yml` - CI/CD workflow
- `.github/ExportOptions.plist` - Signing config
- `composeApp/src/iosMain/` - iOS code
- `iOS_SIDELOAD_GUIDE.md` - Sideload instructions

## What's Included

✅ iOS VideoToolbox transcoding
✅ Android MediaCodec transcoding  
✅ Google Cast SDK support
✅ Device capability detection
✅ Real-time progress UI
✅ Automatic builds

## Ready to Test

Status: Ready for testing and development 🚀
