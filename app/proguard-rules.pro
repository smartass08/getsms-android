# Keep WorkManager worker constructor invoked via reflection.
-keep class dev.otprelay.app.SendWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# BouncyCastle references optional desktop/JCA APIs not present on Android.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
