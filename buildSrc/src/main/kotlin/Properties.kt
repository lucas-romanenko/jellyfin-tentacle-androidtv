import org.gradle.api.Project

/**
 * Helper function to retrieve configuration variable values
 */
fun Project.getProperty(name: String): String? {
	// sample.var --> SAMPLE_VAR
	val environmentName = name.uppercase().replace(".", "_")

	// Environment variables take precedence so CI can override the version from the
	// git tag (e.g. TENTACLE_VERSION) without it being shadowed by gradle.properties.
	return System.getenv(environmentName) ?: findProperty(name)?.toString()
}
