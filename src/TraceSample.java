import io.quarkus.qute.Engine;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Template;
import io.quarkus.qute.trace.ResolveEvent;
import io.quarkus.qute.trace.TemplateEvent;
import io.quarkus.qute.trace.TraceListener;

public class TraceSample {

    public static void main(String[] args) throws InterruptedException {

        Engine engine = Engine.builder()
                .addDefaults()
                .addValueResolver(new ReflectionValueResolver()).build();

        Template template = engine.parse("""
                <html>
                   Hello {name}!
                </html>
                """);

        engine.addTraceListener(new TraceListener() {
            @Override
            public void onStartTemplate(TemplateEvent event) {
                System.err.println("Starting template (id): " + event.getTemplateInstance().getTemplate().getId());
            }

            @Override
            public void onBeforeResolve(ResolveEvent event) {
                System.err.println("Before node resolve: " + event.getTemplateNode());
            }

            @Override
            public void onAfterResolve(ResolveEvent event) {
                System.err.println("After node resolve: " + event.getTemplateNode() + " in " + event.getEllapsedTime() + "ms");
            }

            @Override
            public void onEndTemplate(TemplateEvent event) {
                System.err.println("Rendering template (id): " + event.getTemplateInstance().getTemplate().getId() + " in " + event.getEllapsedTime() + "ms");
            }
        });


        String s = template.data("name", "Quarkus")
                .render();
        System.err.println(s);

    }
}