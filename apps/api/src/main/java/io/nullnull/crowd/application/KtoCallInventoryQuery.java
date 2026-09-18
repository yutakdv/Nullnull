package io.nullnull.crowd.application;

/** Reads {@link KtoCallInventory} from the call-audit. Read-only; it writes nothing. */
public interface KtoCallInventoryQuery {

    KtoCallInventory forRelease(String release);
}
