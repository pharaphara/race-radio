# Load, fitness, fatigue, form

- Load scores the physiological cost of a session; one hour at threshold scores 100. On the bike it
  comes from weighted average power (a 30-second rolling mean raised to the fourth power, averaged,
  then the fourth root) and intensity (weighted average power over threshold power). [formula: Allen
  and Coggan] Running and swimming loads are estimates from heart rate or pace and are less precise
  than cycling's; say so when comparing across sports.
- Fitness is the 42-day exponentially weighted load, fatigue the 7-day one, form is fitness minus
  fatigue, ramp is fitness today minus a week ago. This impulse-response model goes back to
  Banister. [formula: Banister 1975, as Intervals.icu implements it]
- How fast fitness may rise per week depends on the athlete: about 3 to 5 points for a beginner,
  5 to 7 for an intermediate, more for advanced athletes under close monitoring. [convention]
- The ratio of acute to chronic load is retrospective context, never a standalone injury or
  readiness verdict. [evidence: Impellizzeri 2020]
- A recovery week every three to four weeks, at roughly half the usual volume and with no hard
  work, consolidates a block. [convention]
