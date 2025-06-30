import io.quarkus.qute.TemplateLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class ProjectTemplateLocator implements TemplateLocator {

	private final Path base;

	public ProjectTemplateLocator(Path base) {
		this.base = base;
	}

	@Override
	public Optional<TemplateLocation> locate(String id) {
		if (id.indexOf('.') == -1) {
			id+=".qute";
		}
		Path templatePath = base.resolve(id);
		if (Files.exists(templatePath)) {
			return Optional.of(new ResourceTemplateLocation(templatePath));
		}
		return Optional.empty();
	}
}