package com.homeease.backend.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolylineCodecTest {

    /** The worked example from Google's Encoded Polyline Algorithm documentation. */
    private static final String GOOGLE_DOC_EXAMPLE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";

    @Test
    void decodesGoogleDocumentedExampleInLatLngOrder() {
        List<double[]> points = PolylineCodec.decode(GOOGLE_DOC_EXAMPLE);

        assertThat(points).hasSize(3);
        assertThat(points.get(0)).containsExactly(38.5, -120.2);
        assertThat(points.get(1)).containsExactly(40.7, -120.95);
        assertThat(points.get(2)).containsExactly(43.252, -126.453);
    }

    @Test
    void encodeIsInverseOfDecodeForAnIndianRoadSegment() {
        List<double[]> hyderabad = List.of(
                new double[]{17.44850, 78.37480},
                new double[]{17.44912, 78.37566},
                new double[]{17.45101, 78.37690},
                new double[]{17.45500, 78.37201});

        List<double[]> roundTrip = PolylineCodec.decode(PolylineCodec.encode(hyderabad));

        assertThat(roundTrip).hasSameSizeAs(hyderabad);
        for (int i = 0; i < hyderabad.size(); i++) {
            assertThat(roundTrip.get(i)[0]).isEqualTo(hyderabad.get(i)[0], org.assertj.core.data.Offset.offset(1e-9));
            assertThat(roundTrip.get(i)[1]).isEqualTo(hyderabad.get(i)[1], org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void emptyInputYieldsNoPoints() {
        assertThat(PolylineCodec.decode("")).isEmpty();
        assertThat(PolylineCodec.decode(null)).isEmpty();
    }

    @Test
    void rejectsTruncatedPolyline() {
        // Drop the final characters so the last varint never terminates.
        String truncated = GOOGLE_DOC_EXAMPLE.substring(0, GOOGLE_DOC_EXAMPLE.length() - 3);
        assertThatThrownBy(() -> PolylineCodec.decode(truncated)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLatitudeWithoutLongitude() {
        // A single encoded value (one latitude, no longitude).
        String single = PolylineCodec.encode(List.of(new double[]{17.4485, 0.0})).substring(0, 5);
        assertThatThrownBy(() -> PolylineCodec.decode(single)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> PolylineCodec.decode("_p~iF~ps|U ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        // 91.0 degrees latitude is impossible.
        String bad = PolylineCodec.encode(List.of(new double[]{91.0, 10.0}));
        assertThatThrownBy(() -> PolylineCodec.decode(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
