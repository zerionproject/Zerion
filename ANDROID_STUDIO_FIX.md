# Android Studio Configuration Fix

## The Problem
Android Studio is showing: `Activity class {org.briarproject.briar.android.debug/com.professor.zerion.android.splash.SplashScreenActivity} does not exist`

This means Android Studio is using the **OLD** package ID `org.briarproject.briar.android.debug` but looking for activity in the **NEW** package `com.professor.zerion.android.splash`.

## Root Cause Analysis
1. ✅ **build.gradle** - applicationId is correctly set to `com.professor.zerion`
2. ✅ **AndroidManifest.xml** - package is correctly set to `com.professor.zerion`
3. ✅ **Java files** - All migrated to `com.professor.zerion` package
4. ❌ **Android Studio cache** - Still has old configuration cached

## Solutions (Try in Order)

### Solution 1: Invalidate Caches (RECOMMENDED)
1. **File → Invalidate Caches**
2. Check all boxes:
   - Clear file system cache and Local History
   - Clear VCS Log caches and indexes
   - Clear downloaded shared indexes
3. Click **Invalidate and Restart**
4. After restart, wait for indexing to complete
5. **Build → Clean Project**
6. **Build → Rebuild Project**

### Solution 2: Delete Run Configuration
1. **Run → Edit Configurations**
2. Find and delete "briar-android" or "app" configuration
3. Click **Apply** and **OK**
4. **Run → Edit Configurations** again
5. Click **+** → **Android App**
6. Configure:
   - Name: `Zerion`
   - Module: Select `briar-android` or `briar-android.officialDebug`
   - Leave "Launch: Default Activity" selected
7. **Apply** and **Run**

### Solution 3: Manual Activity Specification
1. **Run → Edit Configurations**
2. Create new Android App configuration
3. Set:
   - Name: `Zerion`
   - Module: `briar-android.officialDebug`
   - Launch: **Specified Activity**
   - Activity: `com.professor.zerion.android.splash.SplashScreenActivity`
4. **Apply** and **Run**

### Solution 4: Command Line Build & Install
Skip Android Studio entirely:

```bash
# Open Command Prompt in project root
cd briar-android

# Build the APK
..\gradlew.bat assembleDebug

# Install (connect device first)
adb install build\outputs\apk\official\debug\briar-android-official-debug.apk

# Launch the app
adb shell am start -n com.professor.zerion.debug/com.professor.zerion.android.splash.SplashScreenActivity
```

### Solution 5: Complete Project Re-import
1. Close Android Studio
2. Delete these folders:
   ```
   .idea\
   .gradle\
   briar-android\build\
   ```
3. Open Android Studio
4. **File → Open** (not "Open Recent")
5. Navigate to project folder and open it
6. Let it sync completely
7. Create new run configuration as in Solution 2

## Verification Steps
After applying any solution:

1. Check the run configuration dropdown shows correct name (e.g., "Zerion" not "briar-android")
2. When running, the console should show:
   - Installing APK: `com.professor.zerion.debug`
   - Launching activity: `com.professor.zerion.android.splash.SplashScreenActivity`

## If All Else Fails
The code is 100% correct. The issue is purely Android Studio caching. You can:

1. Use the command line method (Solution 4) - it will work
2. Install Android Studio on a different machine or user account
3. Use a different IDE like IntelliJ IDEA with Android plugin

## What NOT to Do
- Don't change the code - it's correct
- Don't change package names back to old ones
- Don't modify AndroidManifest.xml - it's correct

## Expected Result
Once fixed, the app will:
- Launch as "Zerion"
- Show only Contacts, Private Groups, Settings in menu
- Use package `com.professor.zerion.debug` for debug builds