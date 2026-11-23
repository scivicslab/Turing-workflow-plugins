package com.scivicslab.turingworkflow.plugins.vault;

/**
 * Checks the names a workflow gives the Vault plugin before they become part of a URL or of a command line.
 *
 * <p>A secret path is put into the URL {@code <address>/v1/<path>}: a {@code ..} segment, a {@code ?}, a {@code #} or
 * a {@code %} lets the workflow reach another endpoint with the token it was given. The same path, a namespace or a
 * pod name become arguments of {@code kubectl exec ... vault kv get}: a text that begins with {@code -} is read as an
 * option, such as {@code -address=} or {@code -token=}.</p>
 */
final class VaultNames {

    private VaultNames() {
    }

    /** Returns why {@code path} is not a KV path, or {@code null} when it is. A path is segments of {@code A-Za-z0-9._~-} joined by one {@code /}. */
    static String pathRefusal(String path) {
        if (path == null || path.isEmpty()) {
            return "the path is empty";
        }
        if (path.startsWith("/") || path.startsWith("-")) {
            return "the path must not begin with '" + path.charAt(0) + "'";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                return "the path has an empty segment";
            }
            if (segment.equals(".") || segment.equals("..")) {
                return "the path has a '" + segment + "' segment";
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                boolean ok = c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '~' || c == '-');
                if (!ok) {
                    return "the path has a character that is not allowed in a Vault path: '" + printable(c) + "'";
                }
            }
        }
        return null;
    }

    /** Returns why {@code name} is not a Kubernetes namespace or pod name, or {@code null} when it is. */
    static String kubernetesNameRefusal(String what, String name) {
        if (name == null || name.isEmpty()) {
            return what + " is empty";
        }
        if (!name.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")) {
            return what + " is not a Kubernetes name (lower case letters, digits, '-' and '.', starting and ending with a letter or digit)";
        }
        return null;
    }

    /** Returns why {@code field} cannot be a field name on a command line, or {@code null} when it can. */
    static String fieldRefusal(String field) {
        if (field == null || field.isEmpty()) {
            return "the field name is empty";
        }
        for (int i = 0; i < field.length(); i++) {
            if (Character.isISOControl(field.charAt(i))) {
                return "the field name has a control character";
            }
        }
        return null;
    }

    private static String printable(char c) {
        return Character.isISOControl(c) ? String.format("\\u%04x", (int) c) : String.valueOf(c);
    }
}
