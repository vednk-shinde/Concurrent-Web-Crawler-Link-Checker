package com.vedank.crawler.robots;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The subset of robots.txt a polite crawler needs: Allow/Disallow prefixes
 * for the group matching our user-agent (or {@code *}). The longest
 * matching rule wins, and Allow wins a tie, as in RFC 9309.
 */
public final class RobotsRules {

    private static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

    private record Rule(String prefix, boolean allow) {
    }

    private final List<Rule> rules;

    private RobotsRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static RobotsRules allowAll() {
        return ALLOW_ALL;
    }

    /**
     * @param agentToken our product token, e.g. "LinkCheckerBot"; a group
     *                   naming it takes precedence over the {@code *} group
     */
    public static RobotsRules parse(String robotsTxt, String agentToken) {
        if (robotsTxt == null || robotsTxt.isBlank()) return ALLOW_ALL;
        String agent = agentToken.toLowerCase(Locale.ROOT);

        List<Rule> specific = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        boolean specificSeen = false;

        List<String> groupAgents = new ArrayList<>();
        boolean inRules = false;
        for (String rawLine : robotsTxt.split("\\R")) {
            String line = rawLine.replaceFirst("#.*$", "").trim();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            if (key.equals("user-agent")) {
                if (inRules) { // a user-agent line after rules starts a new group
                    groupAgents.clear();
                    inRules = false;
                }
                groupAgents.add(value.toLowerCase(Locale.ROOT));
            } else if (key.equals("allow") || key.equals("disallow")) {
                inRules = true;
                if (value.isEmpty()) continue; // "Disallow:" with no path allows everything
                Rule rule = new Rule(value, key.equals("allow"));
                for (String ua : groupAgents) {
                    if (ua.equals("*")) {
                        wildcard.add(rule);
                    } else if (agent.contains(ua) || ua.contains(agent)) {
                        specific.add(rule);
                        specificSeen = true;
                    }
                }
            }
        }
        return new RobotsRules(specificSeen ? specific : wildcard);
    }

    /** @param pathAndQuery e.g. {@code /private/page?id=1} */
    public boolean isAllowed(String pathAndQuery) {
        Rule best = null;
        for (Rule rule : rules) {
            if (!pathAndQuery.startsWith(rule.prefix())) continue;
            if (best == null
                    || rule.prefix().length() > best.prefix().length()
                    || (rule.prefix().length() == best.prefix().length() && rule.allow())) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }
}
