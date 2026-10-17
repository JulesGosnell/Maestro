package maestro

import maestro.nearside.NearSideSelector

sealed class OnDeviceElementQuery {

    data class Css(
        val css: String,
    ) : OnDeviceElementQuery()

    /**
     * A Maestro selector resolved on the device, polled there for up to [waitMs] until it matches
     * (smithr#1184). Answers the matches in the order the host's own filter would put them.
     */
    data class NearSide(
        val selector: NearSideSelector,
        val waitMs: Long,
    ) : OnDeviceElementQuery()

}