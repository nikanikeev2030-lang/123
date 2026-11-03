package ru.arenahelper;

import java.util.Locale;


final class TextNormalizer {
    private TextNormalizer() {
    }

    static String normalize(String str) {
        if (str == null) {
            return "";
        }
        String strReplace = str.toLowerCase(Locale.ROOT).replace((char) 1105, (char) 1077);
        StringBuilder sb = new StringBuilder(strReplace.length());
        for (int i = 0; i < strReplace.length(); i++) {
            char cCharAt = strReplace.charAt(i);
            switch (cCharAt) {
                case 'a':
                    cCharAt = 1072;
                    break;
                case 'b':
                    cCharAt = 1074;
                    break;
                case 'c':
                    cCharAt = 1089;
                    break;
                case 'e':
                    cCharAt = 1077;
                    break;
                case 'h':
                    cCharAt = 1085;
                    break;
                case 'k':
                    cCharAt = 1082;
                    break;
                case 'm':
                    cCharAt = 1084;
                    break;
                case 'o':
                    cCharAt = 1086;
                    break;
                case 'p':
                    cCharAt = 1088;
                    break;
                case 't':
                    cCharAt = 1090;
                    break;
                case 'x':
                    cCharAt = 1093;
                    break;
                case 'y':
                    cCharAt = 1091;
                    break;
            }
            if ((cCharAt >= 1072 && cCharAt <= 1103) || Character.isDigit(cCharAt)) {
                sb.append(cCharAt);
            }
        }
        return sb.toString();
    }

    static int distance(String str, String str2) {
        int[] iArr = new int[str2.length() + 1];
        for (int i = 0; i <= str2.length(); i++) {
            iArr[i] = i;
        }
        int[] iArr2 = iArr;
        int i2 = 0;
        while (i2 < str.length()) {
            int[] iArr3 = new int[str2.length() + 1];
            int i3 = i2 + 1;
            iArr3[0] = i3;
            int i4 = 0;
            while (i4 < str2.length()) {
                int i5 = i4 + 1;
                iArr3[i5] = Math.min(iArr3[i4] + 1, Math.min(iArr2[i5] + 1, iArr2[i4] + (str.charAt(i2) == str2.charAt(i4) ? 0 : 1)));
                i4 = i5;
            }
            iArr2 = iArr3;
            i2 = i3;
        }
        return iArr2[str2.length()];
    }
}
