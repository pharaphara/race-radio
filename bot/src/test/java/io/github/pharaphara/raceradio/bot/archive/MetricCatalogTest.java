package io.github.pharaphara.raceradio.bot.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricCatalogTest {

    private static String key(String words) {
        return MetricCatalog.find(words).map(MetricCatalog.Metric::key).orElse("none");
    }

    @Test
    void theSportNamedDecidesBetweenMetricsOfTheSameName() {
        assertThat(key("course efficacité")).isEqualTo("course_efficacite");
        assertThat(key("vélo efficacité")).isEqualTo("velo_efficacite");
        assertThat(key("efficacité")).isEqualTo("velo_efficacite");
        assertThat(key("cadence course")).isEqualTo("course_cadence");
        assertThat(key("vélo cadence")).isEqualTo("velo_cadence");
    }

    @Test
    void wordsMatchInAnyOrderAndAccentsDoNotMatter() {
        assertThat(key("vélo 20 min")).isEqualTo("velo_20min");
        assertThat(key("course 5km")).isEqualTo("course_5km");
        assertThat(key("FC à puissance donnée")).isEqualTo("velo_fc_endurance");
        assertThat(key("VFC")).isEqualTo("vfc");
        assertThat(key("velo_derive")).isEqualTo("velo_derive");
        assertThat(key("météo")).isEqualTo("none");
    }

    @Test
    void everyMetricHasAUniqueKeyAndAtLeastOneWord() {
        assertThat(MetricCatalog.ALL).extracting(MetricCatalog.Metric::key).doesNotHaveDuplicates();
        assertThat(MetricCatalog.ALL).allMatch(m -> !m.words().isEmpty());
    }
}
