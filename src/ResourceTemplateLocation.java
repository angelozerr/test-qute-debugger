import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.quarkus.qute.TemplateLocator.TemplateLocation;
import io.quarkus.qute.Variant;

public class ResourceTemplateLocation implements TemplateLocation{

	private final Path templatePath;

	public ResourceTemplateLocation(Path templatePath) {
		this.templatePath = templatePath;
	}

	@Override
	public Reader read() {
		try {
			return Files.newBufferedReader(templatePath);
		} catch (IOException e) {
			 return null;
		}
	}

	@Override
	public Optional<Variant> getVariant() {
		return Optional.empty();
	}

}