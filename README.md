# bKash Verify - Personal Android App

This project is a personal notification-based payment checker.

## What it does

1. Reads bKash notifications through Android Notification Listener access.
2. Extracts the amount and transaction ID when the notification format matches.
3. Compares the received amount with the expected order amount.
4. Shows VERIFIED or AMOUNT MISMATCH.

## Important

This app does NOT log into bKash, access bKash private APIs, extract credentials/tokens, or bypass bKash security.

The bKash notification format/package name can vary by app version. The parser may therefore need adjustment after testing with an actual notification.

## Build

Open the folder in Android Studio, let Gradle sync, then run on the Android phone.

After installation:
Settings -> Notification access -> bKash Verify -> Allow

Then make a small test payment to your merchant account and check the notification.
