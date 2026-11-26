package bio.cosy.featurecloud.orchestration.helper;

import java.util.List;
import java.util.regex.Pattern;

public class LogHelper {
    private static final Pattern COLORS = Pattern.compile("\u001B\\[[;\\d]*m");

    public static String lastLine(List<String> frames) {
        String[] lines = COLORS.matcher(String.join("", frames)).replaceAll("").strip().split("\\R");
        return lines[lines.length - 1].strip();
    }
}
