package app.vela.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseQualityTest {
    @Test fun movingPreciseCourseIsAccepted() {
        assertEquals(271f, CourseQuality.measured(271f, 14f, 5f, 7f)!!, 0.001f)
    }

    @Test fun unreliableBearingIsRejectedEvenWhenPositionIsAccurate() {
        assertNull(CourseQuality.measured(90f, 16f, 4f, 80f))
    }

    @Test fun aStationaryOrSlowFixCannotSwingTheArrow() {
        assertNull(CourseQuality.measured(140f, 0.2f, 4f, 3f))
    }

    @Test fun aWideSlowFixDoesNotProvideAHeading() {
        assertNull(CourseQuality.measured(90f, 2f, 60f, 10f))
    }

    @Test fun wideFixAtDrivingSpeedMaySupplyAnAccurateBearing() {
        assertEquals(90f, CourseQuality.measured(90f, 12f, 40f, 10f)!!, 0.001f)
    }

    @Test fun courseAtBearingAccuracyBoundaryIsAccepted() {
        assertEquals(45f, CourseQuality.measured(45f, 10f, 5f, 45f)!!, 0.001f)
    }

    @Test fun courseJustOutsideBearingAccuracyBoundaryIsRejected() {
        assertNull(CourseQuality.measured(45f, 10f, 5f, 45.1f))
    }

    @Test fun wrapsNegativeBearings() {
        assertEquals(350f, CourseQuality.measured(-10f, 15f, 4f, null)!!, 0.001f)
    }
}
