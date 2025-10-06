# Privacy Policy for Note Stasher

**Effective Date:** October 5, 2025  
**Developer:** Vibecode  
**Contact:** [vibecode.sam@gmail.com](mailto:vibecode.sam@gmail.com)

---

## 1. Introduction & Scope
This Privacy Policy explains how the Note Stasher Android application ("**Note Stasher**", "**the App**") collects, uses, and protects information when you install or use it on your device. The policy applies globally, including to users in the European Union, United Kingdom, California, and other jurisdictions with specific data protection requirements. By using Note Stasher, you agree to the practices described. If you do not agree, uninstall the App.

This policy is hosted at a stable, publicly accessible URL and is linked from both the Google Play store listing and within the App, satisfying Google Play’s User Data and Data Safety requirements.

---

## 2. Data We Collect or Access
Note Stasher is a local note-taking and drafting tool. It only accesses the information required to provide features you initiate.

- **User-entered content:** Text drafts, message history, document IDs/aliases, and other configuration values that you input.  
- **Optional attachments:** Image files you manually attach to drafts (stored locally within the App’s private storage area).  
- **Local state:** Draft history, attachments, and configuration data stored in Android SharedPreferences and a Room database on your device.

**The App does not collect:**
- Usernames, email addresses, passwords, or other identity credentials.  
- Location data (GPS, network, or other geolocation).  
- Camera, microphone, or sensor data, beyond reading media files you explicitly select.  
- Analytics, crash reports, advertising IDs, or device fingerprints (beyond standard Android OS logging).  
- Background telemetry or automatic uploads; network requests occur only when you use the Send feature.

---

## 3. How We Use the Data
- **Local functionality:** Data stays on your device to support drafting, editing, and previewing notes offline.  
- **Send-to-Script feature (user initiated):** When you tap **Send**, the App packages the selected content (text, attachments, configuration metadata) and transmits it over HTTPS directly to the Google Apps Script endpoint you configured. Note Stasher acts solely as a client and does not copy that data to any Vibecode server or persistent log.  
- **No analytics/marketing use:** We do not use your information for advertising, analytics, or profiling.

> **Prominent Disclosure:** When you use the Send feature, Note Stasher transfers the selected drafts and images to the Google Apps Script endpoint you configured via HTTPS. No data is sent without your explicit action.

---

## 4. Data Sharing & Disclosure
- **No third-party sharing:** We do not share your data with advertisers, analytics providers, or unrelated third parties.  
- **User-directed transfer only:** The only time data leaves your device is when you explicitly initiate the Send feature; in that case, it goes directly to your designated Google Apps Script endpoint.  
- **Legal compliance:** If required by law, we may disclose information we control. Because the App is designed for local storage, the information accessible to us is minimal.

---

## 5. Third-Party Services & SDKs
The App uses standard Android libraries to function:
- AndroidX libraries (Room, WorkManager, Startup, Fragment, Lifecycle, Emoji, Profile Installer)  
- OkHttp and Ktor client for HTTPS requests  
- Google Play In-App Update helper

These libraries operate locally. Note Stasher does **not** integrate Firebase, Google Analytics, AdMob, or other telemetry SDKs. Network requests occur only when you use the Send feature.

---

## 6. Security Practices
- **Local storage security:** Drafts and attachments remain in the App’s sandboxed storage. Android isolates this space from other apps unless you explicitly share files.  
- **Network security:** When you use the Send feature, the App uses HTTPS/TLS to protect data in transit. Ensure the destination Apps Script endpoint uses HTTPS and that you trust the script’s behavior.  
- **Device-level protections:** You may enhance security by enabling device screen locks or full-disk encryption. The App does not implement additional per-file encryption.

---

## 7. Data Retention & Deletion
- **Retention:** Drafts, history entries, attachments, and settings remain on your device until you delete them or uninstall the App. We do not replicate this data off-device.  
- **Managing data:** You can clear drafts/history within the App (where supported) or by clearing the App’s storage via Android settings.  
- **Uninstalling:** Removing the App from your device deletes the local database and attachments stored in the App’s private storage.

If you need assistance, contact us at [vibecode.sam@gmail.com](mailto:vibecode.sam@gmail.com).

---

## 8. Permissions We Request
- **POST_NOTIFICATIONS:** To deliver user-requested notifications (e.g., status updates).  
- **FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC:** Allows short-lived foreground tasks (e.g., sending data) when initiated by you.  
- **INTERNET & ACCESS_NETWORK_STATE:** Required to send content to the configured Apps Script endpoint on demand.  
- **VIBRATE:** Provides optional haptic feedback.  
- **RECEIVE_BOOT_COMPLETED:** Restores scheduled notifications after device reboot if you have them enabled.  
- **READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE (max SDK 29):** Allows you to pick image files and lets the App store attachments in its workspace.

Note Stasher does not request or use camera, microphone, precise location, contacts, SMS, or other sensitive permissions.

---

## 9. Children’s Privacy
The App targets a general audience (ages 13+). We do not knowingly collect personal information from children. If you believe a child has provided personal data, contact us so we can assist with removal.

---

## 10. Legal Rights (EU / UK / California & Other Regions)
Because Note Stasher stores information locally and does not maintain accounts, your rights chiefly involve controlling your own device:
- **EU / UK data subjects:** You may access, correct, or delete your drafts by editing them in the App or uninstalling/clearing data. You may also object or restrict processing by refraining from using the Send feature or removing entries.  
- **California residents:** We do not sell or share personal information for cross-context behavioral advertising.  
- **Other regions:** Similar rights apply; control is in your hands through the App or device settings.  

To make a privacy-related request or ask questions, email [vibecode.sam@gmail.com](mailto:vibecode.sam@gmail.com). We aim to respond within 30 days.

---

## 11. International Transfers
Note Stasher does not transmit data to Vibecode servers. All information remains on your device unless you explicitly send it to your own Apps Script endpoint, which you control.

---

## 12. Future Monetization & Changes
The App currently has no advertising, in-app purchases, or subscriptions. If we add new monetization or analytics features, we will update this policy, revise the Google Play Data Safety form, and notify you before changes take effect.

---

## 13. Changes to This Policy
We may update this policy occasionally. Material changes will be posted at this public link with a revised effective date, and we will notify users within the App when legally required.

---

## 14. Contact Information
For privacy questions or requests, email [vibecode.sam@gmail.com](mailto:vibecode.sam@gmail.com). Support is provided by Vibecode.

---

## 15. Play Store Data Safety Summary
The Google Play store listing for Note Stasher includes a Data Safety section that reflects the disclosures in this policy. This document provides supplemental detail and will be kept aligned with the Data Safety form.

**Thank you for using Note Stasher. Your data stays on your device and under your control.**

