package bio.cosy.featurecloud.orchestration.helper;

import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

public class VersionHelper {
    private static final Pattern VERSION = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(\\.dev|\\.rc(0|[1-9]\\d*))?");


    public static boolean isValidVersion(String version) {
        return StringUtils.isNotBlank(version) && VERSION.matcher(version).matches();
    }

}
