package com.jubitus.travellers.zone;

/** One block-role a {@link ZoneTypeDef} requires to have at least {@code minCount} matches inside
 * its zone's box before creation is allowed, plus the i18n key to show when it's missing. */
public final class BlockRoleRequirement {

    public final String role;
    public final int minCount;
    public final BlockRoleScanner scanner;
    public final String missingErrorKey;

    public BlockRoleRequirement(String role, int minCount, BlockRoleScanner scanner, String missingErrorKey) {
        this.role = role;
        this.minCount = minCount;
        this.scanner = scanner;
        this.missingErrorKey = missingErrorKey;
    }
}
