package com.winterarc.server.model;

public enum SubscriptionTier {
    FREE("Free Edition", 0, 0),
    PRO_MONTHLY("Pro Monthly", 49900, 599),   // ₹499.00 / $5.99
    PRO_YEARLY("Pro Yearly", 399900, 4999),   // ₹3,999.00 / $49.99
    LIFETIME("Lifetime Pro", 999900, 11999);  // ₹9,999.00 / $119.99

    private final String displayName;
    private final long inrPaise;
    private final long usdCents;

    SubscriptionTier(String displayName, long inrPaise, long usdCents) {
        this.displayName = displayName;
        this.inrPaise = inrPaise;
        this.usdCents = usdCents;
    }

    public String getDisplayName() { return displayName; }
    public long getInrPaise() { return inrPaise; }
    public long getUsdCents() { return usdCents; }
}
