package com.scivicslab.turingworkflow.plugins.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * jsoup is at the version that fixes GHSA-65r4-943x-97jj (memory exhaustion through deeply nested namespace
 * declarations, high; fixed in 1.23.2) and GHSA-pmhh-3w7g-xqp8 (Cleaner, 1.23.1). The fetch step parses pages
 * from any site with jsoup.
 */
@Tag("DependencyVersions_261123_oo01")
@DisplayName("plugin-web - jsoup is at the version that fixes the known advisories")
class JsoupVersionTest {

    @Test
    void jsoup_isAtLeast_1_23_2() throws Exception {
        Properties p = new Properties();
        try (InputStream in = org.jsoup.Jsoup.class.getResourceAsStream("/META-INF/maven/org.jsoup/jsoup/pom.properties")) {
            assertNotNull(in, "jsoup's pom.properties");
            p.load(in);
        }
        String v = p.getProperty("version");
        assertTrue(compare(v, "1.23.2") >= 0, "jsoup " + v);
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? Integer.parseInt(x[i].replaceAll("\\D.*", "")) : 0;
            int yi = i < y.length ? Integer.parseInt(y[i].replaceAll("\\D.*", "")) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }
}
