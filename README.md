# ActivityMonitor — ניטור המכשיר

אפליקציית Android מקומית לניטור פעילות במכשיר שבו המשתמש עצמו הפעיל במפורש את ההרשאות הנדרשות.

## מה כלול

- Kotlin + Android SDK + Jetpack.
- MVVM + Repository + Room.
- AccessibilityService לאירועי ממשק שהמערכת ואפליקציות המקור חושפות.
- UsageStatsManager לזמן שימוש באפליקציות כאשר גישה לנתוני שימוש פעילה.
- דגימות סוללה מקומיות.
- ממשק עברי מלא ו-RTL.
- Timeline, פרטי פעולה, אפליקציות, סטטיסטיקות, חיפוש והרשאות.
- אייקון מקורי של האפליקציה ואייקונים אמיתיים של אפליקציות מותקנות דרך PackageManager.
- הגבלת טקסט, סינון שדות שמסומנים כסיסמה, Deduplication ושמירת מידע מקור בסיסי בלבד כברירת מחדל.
- אפשרות להצפנת מידע טכני של מקור הרכיב באמצעות Android Keystore.
- מחיקה ידנית ומדיניות שמירה אוטומטית.
- Unit tests ו-Instrumentation test בסיסי.

## מגבלות מערכת חשובות

האפליקציה אינה עוקפת הרשאות ואינה מנסה לעקוף מסכים מאובטחים. Accessibility אינו מבטיח שכל אפליקציה תחשוף טקסט, View ID, UI tree או אירועים מסוימים. כאשר מידע אינו זמין, הממשק מציג זאת במפורש.

## גרסאות בנייה

- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin: 2.4.20
- compileSdk: 36
- targetSdk: 36
- minSdk: 26
- Room: 2.8.5

הבחירה ב-AGP 9.3.1 נועדה להתאים את סביבת הבנייה ל-Kotlin 2.4.20 ולמטריצת התאימות הרשמית. אין להשתמש בגרסאות דינמיות של dependencies.

## Build

פתחו את התיקייה ב-Android Studio יציב עדכני עם Android SDK 36 ו-JDK 17. לחלופין התקינו Gradle 9.5.0 והגדירו Android SDK מתאים, ואז הריצו:

```text
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

בסביבת היצירה הנוכחית אין Android SDK או Gradle cache מקומי ואין גישה לרשת לצורך הורדתם, לכן פקודות אלה לא הורצו כאן.

## חתימת Release

אין לשמור סיסמת keystore בתוך Git. לאחר יצירת keystore משלכם ניתן להוסיף signingConfig מאובטח באמצעות משתני סביבה או `~/.gradle/gradle.properties`.

דוגמה ליצירת keystore:

```text
keytool -genkeypair -v -keystore activity-monitor-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias activity-monitor
```

לאחר מכן יש לחבר את הקובץ ל-signing config של buildType `release` מחוץ לקוד המקור הציבורי.
