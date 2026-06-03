package com.helix.common.util;

import java.time.Year;
import java.util.concurrent.ThreadLocalRandom;

/** Human-readable reference number generation for deals, counterparties, etc. */
public final class References {

    private References() {
    }

    public static String forDeal() {
        return "HLX-" + Year.now().getValue() + "-" + randomBlock(6);
    }

    public static String forCounterparty() {
        return "CP-" + randomBlock(8);
    }

    public static String forFacility() {
        return "FAC-" + randomBlock(7);
    }

    private static String randomBlock(int len) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
