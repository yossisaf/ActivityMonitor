# BUILD_REPORT

## גרסאות מתוכננות

- Android Gradle Plugin: 8.13.2
- Gradle: 8.13
- Kotlin: 2.4.20
- compileSdk: 36
- targetSdk: 36
- minSdk: 26
- Room: 2.8.5

## תוצאות בפועל בסביבת העבודה הנוכחית

גרסת wrapper: Gradle 8.13 מוגדרת בפרויקט. הופעלה בפועל הפקודה `./gradlew --version`, אך ההורדה נעצרה ב-`java.net.ConnectException` בגלל שאין גישה לרשת/DNS בסביבת העבודה. לא הגיע שלב שבו Gradle עצמו התחיל, ולכן לא יוחס כשל לפרויקט.

| בדיקה | תוצאה | פירוט |
|---|---|---|
| בדיקת מבנה פרויקט | הושלמה | הקבצים והספריות נוצרו |
| בדיקת XML בסיסית | הושלמה | קבצי XML אומתו תחבירית באמצעות parser מקומי |
| Gradle sync | לא הורצה | אין Gradle/Android SDK מקומיים |
| `assembleDebug` | לא הורץ | אין Android SDK/Gradle cache מקומי |
| Unit Tests | לא הורצו | דורשים Gradle + dependencies |
| Lint | לא הורץ | דורש Android build environment |
| Instrumentation Tests | לא הורצו | דורש emulator/device + build |
| `assembleRelease` | לא הורץ | דורש Android build environment |
| התקנת APK | לא בוצעה | לא נוצר APK בסביבה זו |
| אימות חתימה | לא בוצע | אין APK ואין keystore שסופק על ידי המשתמש |
| SHA-256 APK | לא זמין | אין APK שנוצר בפועל |

## סטטוס חתימה

אין APK Release חתום בדו״ח זה. לא נוצר APK ולכן לא נטען מצב חתימה שאינו ניתן לאימות.

## מגבלות ידועות

1. Android אינה מבטיחה שכל אפליקציה תחשוף את כל אירועי הממשק, הטקסט, מזהי הרכיבים או עץ ה-UI דרך Accessibility.
2. UsageStats דורש גישה מפורשת לנתוני שימוש.
3. דגימות סוללה נשמרות כאשר תהליך האפליקציה פעיל ומקבל שינויי סוללה; אין כאן הבטחה לדגימה רציפה כאשר התהליך נהרג.
4. שדות מוגנים כסיסמה מסוננים כאשר Accessibility חושפת את הסימון הזה; אין לטעון שזה מסנן כל תוכן רגיש בכל אפליקציה.
5. אין עקיפה של מסכים מאובטחים או הרשאות מערכת.
