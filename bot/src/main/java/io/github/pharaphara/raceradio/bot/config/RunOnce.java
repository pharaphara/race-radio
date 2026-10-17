package io.github.pharaphara.raceradio.bot.config;

import io.github.pharaphara.raceradio.bot.morning.MorningCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code --race-radio.once=morning} runs the morning call once, prints the message and exits,
 * without Telegram and without publishing anything. Handy to check a deployment, or the wording.
 */
@Component
public class RunOnce implements ApplicationRunner {

    private final String once;
    private final MorningCheck morning;
    private final ConfigurableApplicationContext context;

    public RunOnce(@Value("${race-radio.once:}") String once, MorningCheck morning, ConfigurableApplicationContext context) {
        this.once = once;
        this.morning = morning;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"morning".equals(once)) {
            return;
        }
        MorningCheck.Outcome o = morning.run(false);
        System.out.println("\n" + o.message() + "\n\n(" + (o.worded() ? "rédigé par le modèle" : "texte de repli, sans modèle")
                + ", score " + o.readiness().score() + "/100)");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
