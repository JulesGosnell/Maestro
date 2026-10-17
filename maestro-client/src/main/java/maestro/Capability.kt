package maestro

enum class Capability {
    FAST_HIERARCHY,

    /** The driver resolves an OnDeviceElementQuery.NearSide on the device (smithr#1184). */
    NEAR_SIDE_FIND,
}