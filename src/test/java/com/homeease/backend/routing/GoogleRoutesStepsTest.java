package com.homeease.backend.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Turn-by-turn steps come verbatim from the provider's legs[].steps[]; nothing is synthesised. */
class GoogleRoutesStepsTest {

    private static final String ROUTE = """
            {"legs":[{"steps":[
              {"distanceMeters":220,
               "startLocation":{"latLng":{"latitude":17.4485,"longitude":78.3748}},
               "endLocation":{"latLng":{"latitude":17.4499,"longitude":78.3751}},
               "navigationInstruction":{"maneuver":"DEPART","instructions":"Head north on Hitech City Rd"}},
              {"distanceMeters":540,
               "startLocation":{"latLng":{"latitude":17.4499,"longitude":78.3751}},
               "endLocation":{"latLng":{"latitude":17.4486,"longitude":78.3908}},
               "navigationInstruction":{"maneuver":"TURN_RIGHT","instructions":"Turn right onto Madhapur Main Rd"}},
              {"distanceMeters":10,
               "startLocation":{"latLng":{"latitude":"bad"}},
               "navigationInstruction":{"maneuver":"TURN_LEFT","instructions":"malformed step"}}
            ]}]}
            """;

    @Test
    @DisplayName("provider steps are parsed in order; malformed steps are dropped, not invented")
    void parsesProviderSteps() throws Exception {
        JsonNode route = new ObjectMapper().readTree(ROUTE);
        List<ComputedRoute.RouteStep> steps = GoogleRoutesClient.parseSteps(route);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).instruction()).isEqualTo("Head north on Hitech City Rd");
        assertThat(steps.get(0).maneuver()).isEqualTo("DEPART");
        assertThat(steps.get(1).maneuver()).isEqualTo("TURN_RIGHT");
        assertThat(steps.get(1).distanceMeters()).isEqualTo(540);
        assertThat(steps.get(1).startLat()).isEqualTo(17.4499);
    }

    @Test
    @DisplayName("a route without legs yields no steps (and the 4-arg constructor stays valid)")
    void noLegsNoSteps() throws Exception {
        assertThat(GoogleRoutesClient.parseSteps(new ObjectMapper().readTree("{}"))).isEmpty();
        assertThat(new ComputedRoute("a~l~Fjk~uOwHJy@P", 10, 5, null).steps()).isEmpty();
    }

    @Test
    @DisplayName("the field mask requests the step instructions")
    void fieldMaskRequestsSteps() {
        assertThat(GoogleRoutesClient.FIELD_MASK).contains("routes.legs.steps.navigationInstruction");
    }
}
