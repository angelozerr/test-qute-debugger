import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.debug.adapter.RegisterDebugServerAdapter;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class MainDetail {

    public static void main(String[] args) throws InterruptedException {

        Path templatesProjectPath = Paths.get("src/main/resources/templates/");
        Engine engine = Engine.builder()
                .addEngineListener(new RegisterDebugServerAdapter()) // debug the engine
                .addLocator(new ProjectTemplateLocator(templatesProjectPath))
                .addDefaults()
                //.addValueResolver(new ReflectionValueResolver())
                .build();



        var items = List.of(new Item("foo", 20), new Item("bar", 30), new Item("baz", 40));
        Template template = engine.getTemplate("detail.qute");

        while(true) {
            var templateInstance = template.data("name", "Quarkus")
                    .data("items", items);
            String s = templateInstance
                    .render();
            System.err.println(s);
        }
    }
}