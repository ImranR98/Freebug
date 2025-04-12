# ![Freebug Icon](./app/src/main/res/mipmap-mdpi/ic_launcher_round.webp)&nbsp; Freebug

A "simple and modern" call recorder app for Android (that is, about as simple as Android lets it be - Freebug is definitely not bug-free).

## How it Works

1. A [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) watches all notifications come and go.
2. Each notification is categorized as a "call" or "not call" notification depending on some simple heuristics.
   - We exploit the fact that most call apps show a notification throughout the duration of the call, to tell if a call is ongoing.
   - This lets us take an app-agnostic approach (instead of having to write custom code for WhatsApp, Signal, Google Dialer, etc.) - if it looks like a call, we record it (obviously this can lead to false positives so the logic must be strict).
   - To do this properly, we must be able to read all the notification content, even for notifications that Android categorizes as "sensitive" (including call notifications). For this reason, we must have the `RECEIVE_SENSITIVE_NOTIFICATIONS` permission, which can only be granted via [ADB](https://developer.android.com/tools/adb).
3. If an ongoing call is detected, we use an [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) to record it.
   - An AccessibilityService is used because it is the only kind of service that is allowed to start using the microphone when the recording app is not in the foreground.
   - Accessibility services are also much less prone to being killed by battery optimization, so we use it to restart the notification listener if that gets killed off.
   - Battery optimization for the app in general also must be turned off to further improve resilience.
4. When a call ends, the recording is saved to the Android `Recordings` directory, which can be written to without any file access permissions.

## Work In Progress

This app is still a major work in progress (although very basic functionality does work).
- Probably fails in a lot of untested cases.
- Caller audio very muffled
  - Still audible (at least in quiet environments) and could eventually amplified in post processing
  - Probably not something that can be completely fixed given Android limitations.

Testing TODO:
- [x] Test outgoing call with the system dialer (non-contact number)
- [x] Test outgoing call after app has been swiped away from recents list
- [ ] Test outgoing call with the system dialer (known contact - check name extraction)
- [ ] Test incoming call with the system dialer
- [ ] Test incoming call when device has been asleep for a long time
- [ ] Test after reboot without opening the app
- [ ] Test with an incoming WhatsApp call (confirm name extraction works)
- [ ] Continue to use over an extended period and test anything else that comes to mind (mainly WhatsApp calls: individual vs. group, audio vs. video, etc.).

Development TODO:
- [ ] Clean up the file naming (things like "`.mp4.m4a`", excessive underscores, etc.).
- Longer term things to look into if this app ends up working well:
  - [ ] For the ADB permission step, link to a how-to page
  - [ ] Document in-app why each permission is needed
  - [ ] Allow for user-defined regex pattern for notification detection
  - [ ] General UI improvements and settings additions