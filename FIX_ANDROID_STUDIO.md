# Fix Android Studio Launch Configuration

## The Issue
Android Studio is still looking for the old package name `org.briarproject.briar.android.debug` instead of the new `com.professor.zerion.debug`.

## Solution Steps

### Method 1: Quick Fix in Android Studio
1. **Open Android Studio**
2. **Click on the Run Configuration dropdown** (next to the green play button)
3. **Select "Edit Configurations..."**
4. **Delete the existing "briar-android" configuration** (select it and click the minus button)
5. **Click the "+" button to add a new configuration**
6. **Select "Android App"**
7. **Configure as follows:**
   - Name: `Zerion`
   - Module: `briar-android.officialDebug`
   - Launch Options: Select "Specified Activity"
   - Activity: `com.professor.zerion.android.splash.SplashScreenActivity`
8. **Click "Apply" and "OK"**
9. **Run the app**

### Method 2: Use the Pre-configured Files
I've already created two run configuration files for you:
- `.idea/runConfigurations/app.xml` - Configured with specific activity
- `.idea/runConfigurations/Zerion.xml` - Configured with default activity

**To use them:**
1. **Close Android Studio**
2. **Reopen Android Studio**
3. **You should see "app" or "Zerion" in the run configuration dropdown**
4. **Select one and run**

### Method 3: Full Cache Clear (If above doesn't work)
1. **Close Android Studio**
2. **Delete these folders:**
   ```
   .idea/caches/
   .gradle/
   briar-android/build/
   ```
3. **Open Android Studio**
4. **Let it sync the project**
5. **File → Invalidate Caches and Restart**
6. **After restart, create a new run configuration as in Method 1**

### Method 4: Command Line Build & Install
If Android Studio is still giving issues, you can build and install directly:

```bash
# Build the APK
cd briar-android
../gradlew.bat assembleDebug

# Install the APK (make sure device is connected)
adb install build/outputs/apk/official/debug/briar-android-official-debug.apk

# Launch the app
adb shell am start -n com.professor.zerion.debug/com.professor.zerion.android.splash.SplashScreenActivity
```

## Verification
After fixing, the app should:
- Show "Zerion" as the app name
- Use package `com.professor.zerion.debug` (for debug build)
- Launch successfully with SplashScreenActivity

## Note
The code is correct and compiles successfully. This is purely an Android Studio IDE configuration issue where it's cached the old package name.