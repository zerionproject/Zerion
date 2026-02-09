package info.guardianproject.trustedintents;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.text.TextUtils;

import java.lang.reflect.Constructor;
import java.security.cert.CertificateException;
import java.util.LinkedHashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TrustedIntents {

   private static final Logger LOG =
           Logger.getLogger(TrustedIntents.class.getName());

   private static TrustedIntents instance;

   private static PackageManager pm;

   private final LinkedHashSet<ApkSignaturePin> pinList;

   private TrustedIntents(Context context) {
      pm = context.getPackageManager();
      this.pinList = new LinkedHashSet<ApkSignaturePin>();
   }

   public static TrustedIntents get(Context context) {
      if (instance == null)
         instance = new TrustedIntents(context);
      return instance;
   }

   
   public boolean isReceiverTrusted(ResolveInfo resolveInfo) {
      return isPackageNameTrusted(resolveInfo.activityInfo.packageName);
   }

   
   public boolean isReceiverTrusted(ActivityInfo activityInfo) {
      return isPackageNameTrusted(activityInfo.packageName);
   }

   
   public boolean isReceiverTrusted(Intent intent) {
      if (!isIntentSane(intent))
         return false;
      String packageName = intent.getPackage();
      if (TextUtils.isEmpty(packageName)) {
         packageName = intent.getComponent().getPackageName();
      }
      return isPackageNameTrusted(packageName);
   }

   
   public boolean isPackageNameTrusted(String packageName) {
      try {
         checkTrustedSigner(packageName);
      } catch (NameNotFoundException e) {
         LOG.log(Level.WARNING, e.getMessage(), e);
         return false;
      } catch (CertificateException e) {
         return false;
      }
      return true;
   }

   
   public Intent getIntentFromTrustedSender(Activity activity) {
      Intent intent = activity.getIntent();
      String packageName = getCallingPackageName(activity);
      if (TextUtils.isEmpty(packageName)) {
         return null;
      }
      if (isPackageNameTrusted(packageName)) {
         return intent;
      }
      return null;
   }

   
   public static String getCallingPackageName(Activity activity) {
      ComponentName componentName = activity.getCallingActivity();
      if (componentName == null)
         return null;
      String packageName = componentName.getPackageName();
      return packageName;
   }

   
   private boolean isIntentSane(Intent intent) {
      if (intent == null)
         return false;
      if (TextUtils.isEmpty(intent.getPackage())) {
         ComponentName componentName = intent.getComponent();
         if (componentName == null || TextUtils.isEmpty(componentName.getPackageName())) {
            return false;
         }
      }
      return true;
   }

   
   public boolean addTrustedSigner(Class<? extends ApkSignaturePin> cls) {
      try {
         Constructor<? extends ApkSignaturePin> constructor = cls.getConstructor();
         return pinList.add((ApkSignaturePin) constructor.newInstance((Object[]) null));
      } catch (Exception e) {
         LOG.log(Level.WARNING, e.getMessage(), e);
         throw new IllegalArgumentException(e);
      }
   }

   
   public boolean removeTrustedSigner(Class<? extends ApkSignaturePin> cls) {
      for (ApkSignaturePin pin : pinList) {
         if (pin.getClass().equals(cls)) {
            return pinList.remove(pin);
         }
      }
      return false;
   }

   
   public boolean removeAllTrustedSigners() {
      pinList.clear();
      return pinList.isEmpty();
   }

   
   public boolean isTrustedSigner(Class<? extends ApkSignaturePin> cls) {
      for (ApkSignaturePin pin : pinList) {
         if (pin.getClass().equals(cls)) {
            return true;
         }
      }
      return false;
   }

   public void checkTrustedSigner(String packageName)
           throws NameNotFoundException, CertificateException {
      PackageInfo packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
      checkTrustedSigner(packageInfo.signatures);
   }

   public void checkTrustedSigner(PackageInfo packageInfo)
           throws NameNotFoundException, CertificateException {
      checkTrustedSigner(packageInfo.signatures);
   }

   public void checkTrustedSigner(Signature[] signatures)
           throws NameNotFoundException, CertificateException {
      if (signatures == null || signatures.length == 0)
         throw new CertificateException("signatures cannot be null or empty!");
      for (int i = 0; i < signatures.length; i++)
         if (signatures[i] == null || signatures[i].toByteArray().length == 0)
            throw new CertificateException("Certificates cannot be null or empty!");
      for (ApkSignaturePin pin : pinList)
         if (areSignaturesEqual(signatures, pin.getSignatures()))
            return;

      throw new CertificateException("APK signatures did not match!");
   }

   public boolean areSignaturesEqual(Signature[] sigs0, Signature[] sigs1) {
      if (sigs0 == null || sigs1 == null)
         return false;
      if (sigs0.length == 0 || sigs1.length == 0)
         return false;
      if (sigs0.length != sigs1.length)
         return false;
      for (int i = 0; i < sigs0.length; i++)
         if (!sigs0[i].equals(sigs1[i]))
            return false;
      return true;
   }

   public void startActivity(Context context, Intent intent) throws CertificateException {
      if (!isIntentSane(intent))
         throw new ActivityNotFoundException("The intent was null or empty!");
      String packageName = intent.getPackage();
      if (TextUtils.isEmpty(packageName)) {
         packageName = intent.getComponent().getPackageName();
         intent.setPackage(packageName);
      }
      try {
         checkTrustedSigner(packageName);
      } catch (NameNotFoundException e) {
         LOG.log(Level.WARNING, e.getMessage(), e);
         throw new ActivityNotFoundException(e.getLocalizedMessage());
      }
      context.startActivity(intent);
   }
}
