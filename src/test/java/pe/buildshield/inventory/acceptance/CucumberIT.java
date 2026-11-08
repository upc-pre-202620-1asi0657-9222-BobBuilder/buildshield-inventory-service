package pe.buildshield.inventory.acceptance;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;

/** Escenarios de aceptación (.feature en español) por HTTP contra el servicio completo con PostgreSQL real y Kafka embebido. */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "pe.buildshield.inventory.acceptance")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary, html:target/cucumber-report.html, json:target/cucumber.json")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
// Historias escritas antes de implementarse: se marcan @pendiente y se excluyen hasta tener sus pasos.
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @pendiente")
public class CucumberIT {
}
