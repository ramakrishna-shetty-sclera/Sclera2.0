package com.sclera.applicationplane.helper.domain;

/**
 * Physical hierarchy levels. A BUILDING has no parent; a FLOOR belongs to a
 * BUILDING; a LOCATION belongs to a FLOOR.
 */
public enum LocationType {
    BUILDING,
    FLOOR,
    LOCATION
}
