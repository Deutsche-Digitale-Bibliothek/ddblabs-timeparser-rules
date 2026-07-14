package de.deutsche_digitale_bibliothek.timeparser.repository;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalIdComparatorTest {

    private final NaturalIdComparator comparator = NaturalIdComparator.INSTANCE;

    @Test
    void sortsSimpleAndGeneratedIdsNaturally() {
        List<String> ids = new ArrayList<>(List.of(
                "R10", "R2", "R1_10", "R1_2", "R1",
                "R309_10", "R309_2", "R309", "R02",
                "R99999999999999999999"
        ));

        ids.sort(comparator);

        assertThat(ids).containsExactly(
                "R1", "R1_2", "R1_10", "R2", "R02",
                "R10", "R309", "R309_2", "R309_10",
                "R99999999999999999999"
        );
    }

    @Test
    void fulfillsComparatorContractForMixedIds() {
        List<String> ids = List.of(
                "R1", "R1_2", "R1_10", "R2", "R02", "R10",
                "R290", "R290_2", "R309", "R309_2", "T2", "T10"
        );

        for (String left : ids) {
            for (String right : ids) {
                assertThat(Integer.signum(comparator.compare(left, right)))
                        .isEqualTo(-Integer.signum(comparator.compare(right, left)));
                for (String last : ids) {
                    if (comparator.compare(left, right) <= 0 && comparator.compare(right, last) <= 0) {
                        assertThat(comparator.compare(left, last)).isLessThanOrEqualTo(0);
                    }
                }
            }
        }
    }
}
