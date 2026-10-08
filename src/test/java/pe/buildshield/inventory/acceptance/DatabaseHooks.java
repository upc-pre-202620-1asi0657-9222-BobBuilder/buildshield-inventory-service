package pe.buildshield.inventory.acceptance;

import io.cucumber.java.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import pe.buildshield.inventory.support.MutableClock;
import pe.buildshield.inventory.support.TestDatabase;

import java.time.Instant;

/** Antes de cada escenario: base vacía (tablas del servicio) y reloj en la hora actual. */
public class DatabaseHooks {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Before(order = 0)
    public void resetState() {
        TestDatabase.reset(jdbc);
        clock.setTo(Instant.now());
    }
}
