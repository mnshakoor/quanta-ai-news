package news;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fictional offline dataset, used only when no live feed can be reached.
 * Outlet names are real so that ratings resolve, but every headline, angle and
 * statistic here is illustrative and is labelled as such in the interface.
 */
final class DemoData {
    private DemoData() {}

    private record Row(String source, String angle) {}

    static List<Models.Story> stories(SourceRegistry reg) {
        List<Models.Story> out = new ArrayList<>();
        add(out, reg, 1, "Lawmakers unveil bipartisan framework for a national data-privacy law",
            "House and Senate negotiators released a compromise proposal that would set a single federal standard for how companies collect, store, and sell personal data, overriding patchwork state laws.",
            new Row("The Guardian", "Privacy advocates call it a long-overdue step, while noting enforcement gaps remain."),
            new Row("New York Times", "The deal is described as a fragile bipartisan breakthrough after years of failed efforts."),
            new Row("The Hill", "Key committee leaders tout the compromise; details of preemption still contested."),
            new Row("Politico", "Sources say the bill's chances hinge on tech-industry lobby concessions."),
            new Row("Wall Street Journal", "Business groups warn the compliance costs would fall hardest on smaller firms."),
            new Row("Fox News", "Commentators question whether the bill goes too far in restricting data use."));
        add(out, reg, 2, "City plan to expand congestion-pricing zone advances despite suburban backlash",
            "Transportation officials approved a second phase of downtown tolls, drawing praise from transit groups and opposition from suburban commuters and small businesses.",
            new Row("New York Times", "Officials frame the expansion as essential to fund ailing subway service."),
            new Row("Washington Post", "The rollout is examined through the lens of equity and who can afford to drive."),
            new Row("CNN", "Live coverage shows dueling rallies outside city hall before the vote."),
            new Row("Vox", "Cites the toll as a textbook case of congestion economics."),
            new Row("HuffPost", "Advocates argue the revenue is needed to keep fares affordable."),
            new Row("AP", "The AP reports the vote tally and the timeline for the toll rollout."));
        add(out, reg, 3, "Fed officials signal rates may hold steady as inflation cools further",
            "New inflation data came in below forecasts for the third straight month, and policymakers suggest borrowing costs may stay where they are through the middle of next year.",
            new Row("Reuters", "Markets priced in a higher probability of an extended pause after the data release."),
            new Row("AP", "The AP outlines what the cooler figures mean for households and mortgage rates."),
            new Row("Wall Street Journal", "Columnists debate whether the Fed risks reversing course if prices tick back up."),
            new Row("New York Times", "Economists quoted by the Times focus on the labor market's mixed signals."),
            new Row("The Guardian", "The Guardian notes the relief may be uneven for renters and hourly workers."),
            new Row("Fox News", "Commentators argue the pause ignores household cost pressures."));
        add(out, reg, 4, "Federal court strikes down state ban on flavored tobacco products",
            "A federal judge ruled that the state's flavor ban conflicts with federal law, a decision health groups say will roll back years of youth-vaping progress.",
            new Row("Reuters", "Reuters reports the ruling's legal reasoning and the expected appeal."),
            new Row("AP", "The AP lays out the timeline for an emergency stay request."),
            new Row("New York Times", "Public-health experts criticize the decision as a setback for prevention."),
            new Row("Reason", "Libertarian commentators celebrate the ruling as a win for consumer choice."),
            new Row("The Hill", "The Hill summarizes reactions from both state officials and industry groups."),
            new Row("Fox News", "Coverage emphasizes small retailers' relief over the ban's repeal."));
        add(out, reg, 5, "Leading AI labs agree to voluntary safeguards ahead of new model releases",
            "Seven major AI developers signed voluntary commitments covering model testing, watermarking, and disclosure of safety incidents before shipping their next frontier models.",
            new Row("New York Times", "The Times frames the pledges as a stopgap while Congress debates binding rules."),
            new Row("The Guardian", "Critics say voluntary promises lack enforcement mechanisms."),
            new Row("Vox", "Vox explains why self-regulation is unlikely to satisfy safety researchers."),
            new Row("Reuters", "Reuters details the exact commitments and which companies signed on."),
            new Row("Politico", "Politico reports the political pressure behind the late agreement."),
            new Row("Wall Street Journal", "The Journal notes the pledges may reassure investors without slowing development."),
            new Row("Daily Wire", "Coverage warns the commitments could become a backdoor to heavy regulation."));
        add(out, reg, 6, "Smoke from regional wildfires triggers air-quality alerts across several states",
            "Large fires in the northwest pushed unhealthy particulate levels into major metro areas, prompting school closures and advice to stay indoors.",
            new Row("AP", "The AP tracks the smoke plume's path using satellite data."),
            new Row("BBC News", "The BBC reports on evacuation zones and emergency shelter plans."),
            new Row("NPR", "NPR explains the health risks of prolonged exposure to fine particles."),
            new Row("USA Today", "USA Today publishes a guide to air-quality index levels for readers."),
            new Row("The Hill", "The Hill reports on state funding requests for firefighting resources."),
            new Row("Washington Post", "The Post links the worsening fire season to broader climate trends."));
        add(out, reg, 7, "Auto workers' strike enters third week as contract talks stall",
            "Nearly 40,000 workers remain on picket lines after negotiations broke down over wages and job security, with both sides blaming the other for the impasse.",
            new Row("Fox News", "Coverage highlights union demands' impact on dealerships and consumer prices."),
            new Row("NY Post", "The Post reports on frustration among non-union plant workers."),
            new Row("Daily Wire", "Commentary argues the strike is politically motivated ahead of the election."),
            new Row("Reason", "Reason questions whether union leadership is bargaining in good faith."),
            new Row("Washington Examiner", "The Examiner tallies the strike's cost to suppliers."),
            new Row("Wall Street Journal", "The Journal analyzes the financial cost of the strike to the automakers."));
        add(out, reg, 8, "New poll finds voters increasingly optimistic about the economy, worried about housing",
            "Consumer confidence has climbed to a two-year high, but a majority of respondents now say housing costs are their top financial concern.",
            new Row("Reuters", "Reuters breaks down the poll's methodology and margin of error."),
            new Row("Politico", "Politico examines what the numbers mean for the campaign trail."),
            new Row("The Hill", "The Hill charts the rise in optimism across income groups."),
            new Row("Fox News", "Coverage focuses on how housing anxiety cuts against the administration's message."),
            new Row("Washington Post", "The Post interviews voters about the gap between national statistics and local rents."),
            new Row("Washington Times", "The Times links the housing squeeze to tight supply and zoning rules."));
        add(out, reg, 9, "FDA approves a new class of migraine medication",
            "The agency cleared a first-of-its-kind oral treatment designed to head off migraines before they start, with a price that experts say may limit access.",
            new Row("AP", "The AP covers the clinical trial data behind the approval."),
            new Row("BBC News", "The BBC explains how the drug differs from existing therapies."),
            new Row("NPR", "NPR quotes neurologists about who may benefit most."),
            new Row("USA Today", "USA Today lists copay assistance programs for patients."),
            new Row("Washington Post", "The Post reports on patient advocates pushing for insurance coverage."),
            new Row("NY Post", "The Post's coverage focuses on the list price and potential backlash."));
        add(out, reg, 10, "Disagreement grows over proposed changes to asylum rules",
            "A new proposal to speed up asylum screenings has split lawmakers along party lines, with civil-liberties groups pledging immediate lawsuits.",
            new Row("The Guardian", "The Guardian warns the fast-track process would deny due process."),
            new Row("Vox", "Vox explains the legal standard the proposal would change."),
            new Row("HuffPost", "Coverage centers on advocates' fears for vulnerable applicants."),
            new Row("Fox News", "Fox frames the plan as overdue enforcement of border laws."),
            new Row("Daily Wire", "Commentary argues the changes are needed to ease case backlogs."),
            new Row("AP", "The AP reports the text of the proposal and the expected court challenges."));
        return out;
    }

    private static void add(List<Models.Story> out, SourceRegistry reg, int n, String title, String summary, Row... rows) {
        List<Models.Article> arts = new ArrayList<>();
        Instant base = Instant.now().minusSeconds(n * 2400L);
        int i = 0;
        for (Row r : rows) {
            Models.Source s = reg.byName(r.source());
            if (s == null) continue;
            String link = (s.homepage().isEmpty() ? "https://example.org" : s.homepage());
            arts.add(new Models.Article(s, r.angle(), r.angle(), link, null, base.plusSeconds(i++ * 900L)));
        }
        Models.Story st = StoryBuilder.build(arts, title, summary);
        out.add(new Models.Story("demo" + n, st.title(), st.summary(), st.category(), st.image(),
                st.firstSeen(), st.lastUpdated(), st.articles(), st.left(), st.center(), st.right(),
                st.sourceCount(), st.blindspot(), st.polarized(), 100 - n));
    }
}
