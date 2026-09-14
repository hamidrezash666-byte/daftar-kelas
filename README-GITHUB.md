# ساخت خودکار APK با GitHub Actions

1. همه فایل‌های این پروژه را داخل یک GitHub repository قرار دهید.
2. با هر Push روی `main` یا `master`، ساخت APK به‌صورت خودکار شروع می‌شود.
3. از بخش **Actions** اجرای **Build Android APK** را باز کنید.
4. پس از پایان موفق، از بخش **Artifacts** فایل `دفتر-کلاس-debug-apk` را دانلود کنید.
5. برای اجرای دستی هم در Actions گزینه **Run workflow** وجود دارد.

> این Workflow روی Ubuntu اجرا می‌شود و برای Gradle 8.10.2 از JDK 17 استفاده می‌کند.
