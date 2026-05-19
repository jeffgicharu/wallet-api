package com.wallet.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Single place that formats a money amount for any text shown to a
 * user. Validation, limit, and insufficient-funds messages are surfaced
 * verbatim by the SPA (as toasts), so they must read exactly like every
 * other amount in the product: "KES 1,234.50" — currency prefix, grouped
 * thousands, two decimals. Previously these messages emitted raw
 * BigDecimal ("300000.00", "KES 34094.65"), which looked unformatted
 * next to the polished UI.
 */
public final class Money {

    private Money() {
    }

    private static final DecimalFormat FMT;

    static {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
        s.setGroupingSeparator(',');
        s.setDecimalSeparator('.');
        FMT = new DecimalFormat("#,##0.00", s);
    }

    /** "KES 1,234.50" */
    public static String format(BigDecimal amount) {
        return "KES " + FMT.format(amount == null ? BigDecimal.ZERO : amount);
    }
}
