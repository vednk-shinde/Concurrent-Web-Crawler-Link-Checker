package com.vedank.crawler.robots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RobotsRulesTest {

    @Test
    void wildcardGroupDisallowsPrefixes() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /admin
                Disallow: /tmp/  # trailing comment
                """, "LinkCheckerBot");

        assertFalse(rules.isAllowed("/admin"));
        assertFalse(rules.isAllowed("/admin/users?id=1"));
        assertFalse(rules.isAllowed("/tmp/file"));
        assertTrue(rules.isAllowed("/tmp"));
        assertTrue(rules.isAllowed("/"));
    }

    @Test
    void longestMatchWinsAndAllowWinsTies() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /docs/
                Allow: /docs/public/
                Disallow: /same
                Allow: /same
                """, "LinkCheckerBot");

        assertFalse(rules.isAllowed("/docs/internal"));
        assertTrue(rules.isAllowed("/docs/public/intro"));
        assertTrue(rules.isAllowed("/same"));
    }

    @Test
    void groupNamingOurAgentOverridesWildcard() {
        String txt = """
                User-agent: *
                Disallow: /

                User-agent: LinkCheckerBot
                Disallow: /private
                """;

        RobotsRules ours = RobotsRules.parse(txt, "LinkCheckerBot");
        assertTrue(ours.isAllowed("/public"));
        assertFalse(ours.isAllowed("/private"));

        RobotsRules someoneElse = RobotsRules.parse(txt, "OtherBot");
        assertFalse(someoneElse.isAllowed("/public"));
    }

    @Test
    void multipleAgentsCanShareAGroup() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: googlebot
                User-agent: linkcheckerbot
                Disallow: /shared
                """, "LinkCheckerBot");

        assertFalse(rules.isAllowed("/shared"));
    }

    @Test
    void emptyDisallowAndEmptyFileAllowEverything() {
        assertTrue(RobotsRules.parse("User-agent: *\nDisallow:\n", "Bot").isAllowed("/anything"));
        assertTrue(RobotsRules.parse("", "Bot").isAllowed("/anything"));
        assertTrue(RobotsRules.allowAll().isAllowed("/anything"));
    }
}
