/**
 * AutoBattle Battle Engine v2
 * Deterministic auto-battle simulator. Works in browser and Node.js.
 *
 * Full turn sequence:
 *   0. Active player's shields decay by 1; then active player takes damage equal to their poison stacks and poison decrements by 1
 *   1. Active player draws a card (or loses if deck is empty)
 *   2. Active player gains 1 basic energy
 *   3. Opponent's each_opponent_turn permanents trigger (random order, seeded)
 *   4. Active player's each_turn permanents trigger (beginning of turn)
 *   5. Active player plays cards (greedy; ephemeral energy spent first, cheapest card first)
 *   6. Active player's end_of_turn permanents trigger
 *   6b. Armor recovery (both boards reset curArmor to base)
 *   6c. Combat phase: active player's total creature melee minus opponent's total permanent
 *       armor = damage dealt to opponent (blocked by shields). Fires on_life_loss /
 *       on_damage_dealt passives. Checked for win condition.
 *   7. Ephemeral energy clears
 *   8. Discard random cards until hand ≤ 10
 *
 * Card types:
 *   Permanent    — stays on board; has triggers; can optionally have a health stat
 *   Non-permanent — fires on_enter effects then goes to discard (e.g. spells)
 *   Inferred from supertype: "spell" → non-permanent; all others → permanent.
 *   card_type field ("permanent"/"non_permanent") overrides if present.
 *
 * Trigger events:
 *   on_enter           — card is played and enters play
 *   on_leave           — card leaves play for any reason
 *   on_death           — card is killed (hp → 0) or explicitly killed by effect
 *   each_turn          — beginning of controller's turn (alias: turn_start)
 *   end_of_turn        — end of controller's turn
 *   each_opponent_turn — fires after opponent draws + gains 1 energy
 *
 * Damage rules:
 *   Only permanents with a health stat (stats_json.health) can be damaged or killed.
 *   Permanents without health can only be removed by destroy effects.
 *   Players take damage only from explicit deal_damage_to_player effects.
 *
 * Energy types:
 *   basic     — persists between turns
 *   ephemeral — clears at end of turn; spent before basic when paying costs
 *
 * Browser: window.BattleEngine = { runCohort, simulateMatch, recordBattle, hashResults }
 * Node.js: const BE = require('./battle-engine');
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) module.exports = factory();
  else root.BattleEngine = factory();
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // Runaway-token guard, mirroring validate.go's maxTokenCopiesPerFire and
  // validate.py's MAX_TOKEN_COPIES_PER_FIRE -- all three engines must agree or
  // shards come back disputed. create_token_copy is the only effect that adds
  // an UNBOUNDED number of permanents: its amount can come from count_source,
  // and when that counts a tag the tokens themselves carry, every fire creates
  // as many tokens as already exist and the board doubles each turn. Found
  // live 2026-09-20 (Rabbit Queen): validators hung forever instead of
  // erroring, so cohorts never finalized. Measured on that payload: uncapped,
  // 777 and 177 never finished; 17 took ~6s; 10 takes ~2s. 10 also matches the
  // clamp already used by return_opponent_permanent and
  // destroy_permanents_by_cost, and no sane card creates 10+ tokens in one fire.
  const MAX_TOKEN_COPIES_PER_FIRE = 10;

  // v4.18 -- coinlink (printed as "Avarice") mints Gold Coin tokens when the
  // attack connects. Hardcoded card id, the same convention as
  // INFINITE_CARD_IDS below and inc/beginner_mode.php's BEGINNER_INFINITE_IDS:
  // the keyword is NAMED for this one token, so unlike create_token_copy it
  // carries no card_id of its own on the passive. Must stay in sync with
  // validate.go's coinlinkTokenID, validate.py's COINLINK_TOKEN_ID and
  // engine/engine.go. A coin is a cost-0 `treasure` Relic whose own each_turn
  // sacrifices a treasure relic for 1 ephemeral energy, so the payoff lands on
  // the NEXT turn and expires at the end of it -- that delay is the whole
  // balance of the keyword. A payload that omits card 812 mints nothing rather
  // than erroring (see shell/regression: a balance harness needs transitive
  // token closure or this silently no-ops).
  const COINLINK_TOKEN_ID = 812;

  // ── Seeded RNG (Mulberry32) ──────────────────────────────────────────────────
  function RNG(seed) {
    let s = seed >>> 0;
    return function () {
      s = (s + 0x6D2B79F5) >>> 0;
      let t = Math.imul(s ^ (s >>> 15), 1 | s);
      t = (Math.imul(t ^ (t >>> 7), 61 | t)) >>> 0;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function shuffle(arr, rand) {
    const a = arr.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(rand() * (i + 1));
      [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
  }

  // ── Constants ────────────────────────────────────────────────────────────────
  const STARTING_LIFE = 100;
  const HONOR_TO_WIN  = 100;
  const MAX_TURNS     = 600;

  // Human-readable labels for chaos_effect slugs, used only for the
  // recordBattle() text log -- the mechanical effect itself is applied via
  // the raw slug elsewhere in this file regardless of this map.
  const CHAOS_LABELS = {
    extra_draw:                    'Extra Draw (+1 card each turn)',
    extra_energy:                  'Extra Energy (+1 basic energy each turn)',
    creatures_enter_melee_boosted: 'Rallying Charge (creatures enter with +1 Melee)',
    permanents_enter_armored:      'Ironclad Formation (permanents enter with +1 Armor)',
    honor_threshold_50:            'Short War (honor victory at 50, not 100)',
    poison_tick:                   'Toxic Air (each player gains 1 poison at turn start)',
    mill_tick:                     'Slow Rot (each player mills 1 at turn start)',
    self_creature_damage:          'Friendly Fire (2 damage to a random creature you control at turn start)',
    recycle_tick:                  'Reclamation (each player recycles 1 at turn start)',
    player_damage_3:               'Creeping Doom (each player takes 3 damage at turn start)',
    honor_loss_1:                  'Fading Glory (each player loses 1 Honor at turn start)',
    discard_tick:                  'Butterfingers (each player discards a card at turn start)',
    sacrifice_creature:            'Blood Price (each player sacrifices a creature at turn start)',
    sacrifice_relic:               'Crumbling Relics (each player sacrifices a relic at turn start)',
    life_gain_3:                   'Vital Surge (each player gains 3 Life at turn start)',
    spell_tax:                     'Arcane Toll (Spells cost +1 energy)',
    creature_tax:                  'Conscription Fee (Creatures cost +1 energy)',
    relic_tax:                     'Import Duty (Relics cost +1 energy)',
    enchantment_tax:               'Warded Ground (Enchantments cost +1 energy)',
  };
  const MAX_HAND      = 10;

  // parseChaosEffects splits the cohort's chaos_effect payload field (a
  // comma-joined list of 0-2 slugs -- see Chaos Arena selection in
  // inc/cohort_manager.php) into a slug->count map. A slug appearing twice
  // (the same effect picked for both of a cohort's 2 chaos slots) means that
  // effect's single-instance action should be applied twice ("doubled"),
  // which is why every consumer below is count-based (0/1/2) rather than a
  // simple presence check. Mirrors shell/validate.go/shell/validate.py.
  function parseChaosEffects(chaos) {
    const counts = {};
    if (!chaos) return counts;
    for (const slug of chaos.split(',')) {
      if (slug) counts[slug] = (counts[slug] || 0) + 1;
    }
    return counts;
  }

  // Chaos Arena modifiers resolved once per player's own turn (step 0b in
  // _simSingleGame/recordBattle), each self-inflicted against the active
  // player (ap). Every effect type referenced here already exists as a
  // normal card effect -- this table just gives each a Chaos Arena slug and
  // a fixed magnitude; no new mechanics beyond
  // deal_damage_to_own_random_creature (added specifically for
  // self_creature_damage). Mirrors shell/validate.go/shell/validate.py.
  const CHAOS_TURN_START_EFFECTS = [
    { slug: 'poison_tick',          eff: { type: 'gain_poison', amount: 1 } },
    { slug: 'mill_tick',            eff: { type: 'mill_self', amount: 1 } },
    { slug: 'self_creature_damage', eff: { type: 'deal_damage_to_own_random_creature', amount: 2 } },
    { slug: 'recycle_tick',         eff: { type: 'recycle_and', amount: 1 } },
    { slug: 'player_damage_3',      eff: { type: 'deal_damage_to_self', amount: 3 } },
    { slug: 'honor_loss_1',         eff: { type: 'lose_honor', amount: 1 } },
    { slug: 'discard_tick',         eff: { type: 'discard_self', amount: 1 } },
    { slug: 'sacrifice_creature',   eff: { type: 'sacrifice_own_permanent', amount: 1, target_supertype: 'creature' } },
    { slug: 'sacrifice_relic',      eff: { type: 'sacrifice_own_permanent', amount: 1, target_supertype: 'relic' } },
    { slug: 'life_gain_3',          eff: { type: 'gain_life', amount: 3 } },
  ];

  // ── Player factory ───────────────────────────────────────────────────────────
  // resolveStarting{Life,Honor,Shields}: a slot's per-side starting-stat
  // override (see slot.starting_life/starting_honor/starting_shields in
  // the payload -- unlike payload-wide starting_life/honor_to_win, these
  // can differ between the two sides of the same match) takes priority
  // over the payload-wide startingLife override (beginner-mode only),
  // which takes priority over the true default. Currently only populated
  // for bot-owned slots in Bot Challenge Arena (see
  // inc/cohort_manager.php's generate_shard_payload()). Mirrors
  // shell/validate.go/shell/validate.py.
  function resolveStartingLife(override, payloadWide) {
    if (override?.life != null) return override.life;
    if (payloadWide != null) return payloadWide;
    return STARTING_LIFE;
  }
  function resolveStartingHonor(override) { return override?.honor ?? 0; }
  function resolveStartingShields(override) { return override?.shields ?? 0; }
  function resolveStartingExtraCards(override) { return override?.extraCards ?? 0; }
  // Extracts a payload slot's per-side starting-stat override fields into
  // the {life, honor, shields, extraCards} shape resolveStarting* expects.
  function slotStartOverride(s) { return { life: s?.starting_life, honor: s?.starting_honor, shields: s?.starting_shields, extraCards: s?.starting_extra_cards }; }

  function mkPlayer(cards, championId, startingLife, startingHonor, startingShields) {
    return {
      life:      startingLife ?? STARTING_LIFE,
      honor:     startingHonor ?? 0,
      energy:    0,
      ephemeral: 0,
      restrictedEnergy: [], // list of {amount, tag, targetSupertype} -- see canAfford/spendEnergy
      shields:   startingShields ?? 0,
      poison:    0,
      hand:      [],
      deck:      cards.slice(),
      board:     [],
      discard:   [],
      championId:      championId ?? null,
      championCostInc: 0,
      championInZone:  (championId != null),
      // Battle plan energy_hold_scope bookkeeping (see playOneCard): these
      // two are genuinely different conditions -- e.g. if the very first
      // card played was free, hasPlayedFirstCard becomes true while
      // hasSpentFirstEnergy does not, so 'first_play' and 'first_spend'
      // need their own independent flag each.
      hasPlayedFirstCard:  false,
      hasSpentFirstEnergy: false,
      // Time Walk (v4.12): additional turns banked for THIS player. Consumed
      // one per turn end, so two grants really are two extra turns. Lives on
      // the player rather than a loop-local so that granting the OPPONENT a
      // turn (a drawback) is the same mechanism pointed the other way.
      extraTurns: 0,
      // v4.42 turn counters. GROSS per-turn totals, keyed by metric
      // ('life_gained', 'cards_drawn', ...), zeroed for BOTH players at the
      // start of every turn -- see the reset in each turn loop.
      //
      // Gross, not the net snapshot updateTurnStats() uses: life_gained and
      // life_lost are deliberately separate counters, so "gained 3 life then
      // lost 4" has to read as a gain of 3, which a net delta cannot express.
      // That is why these are instrumented at the mutation sites despite the
      // snapshot precedent right above updateTurnStats -- the two measure
      // different things.
      turnTally: {},
    };
  }

  // ── Champion helper ───────────────────────────────────────────────────────────
  // Redirects champion card from discard/hand to champion zone when it leaves board.
  function championLeave(owner, cardId, log, cardDefs) {
    if (owner.championId !== null && cardId === owner.championId) {
      owner.championInZone = true;
      owner.championCostInc++;
      const nm = cardDefs[cardId]?.name ?? cardId;
      if (log) log.push(`  ${nm} returned to Champion Zone (cost now ${(cardDefs[cardId]?.cost ?? 0) + owner.championCostInc})`);
      return true;
    }
    return false;
  }

  // A create_token_copy entry exiles instead of following the normal
  // destroy->discard / bounce->hand destination when it leaves play -- same
  // "never lands anywhere" semantics the existing (dormant) Exile effects
  // already use elsewhere, just triggered by leaving play instead of a
  // dedicated exile_* effect. Called at every discard/hand push site instead
  // of appending directly, right alongside the existing championLeave gate.
  // Every card arriving in ANY discard pile passes through here -- the 16
  // direct pl.discard.push() sites that used to exist each represented a
  // different route (a permanent dying, a hand discard, a discard paid as a
  // cost, self-mill, opponent mill, a resolved spell, a tutor fetching to the
  // pile) and a replacement effect has to be honoured on all of them or it
  // leaks silently: the card simply lands in the discard as before, with no
  // error to notice. Owner-only by design -- nothing here touches the
  // opponent or a battle plan, which is what keeps the signature small enough
  // to thread through every site.
  // Reshuffle (v4.11): a REPLACEMENT effect. The card never reaches the
  // discard at all -- it goes back into its owner's library instead, from
  // whatever route brought it here: dying on board, discarded from hand, paid
  // as a cost, milled off the top by either player, or a resolved spell.
  //
  // Checked against the CARD DEFINITION, not a board entry, because most of
  // those routes have no entry: a card milled out of the deck was never in
  // play. That is also why a `requires` gate disqualifies the printing --
  // requires is evaluated against an entry's board state, and there is none
  // here, so an ungated printing is the only one this can honour.
  //
  // Exile still beats it: an exiled card never arrives, so it never asks.
  function cardReshuffles(cardId, cardDefs) {
    const ps = cardDefs[cardId]?.effects_json?.passives;
    if (!ps) return false;
    for (const p of ps) if (p.type === 'reshuffle' && !p.requires) return true;
    return false;
  }

  // v4.29: the optional effect a Reshuffle passive fires when it actually
  // bounces the card back. Asked for so Reshuffle can carry a real cost rather
  // than being a flat anti-mill keyword -- "when Larval Gghulbb is reshuffled,
  // lose 7 Honor" -- which gives a deck leaning on it something to weigh
  // against a mill deck instead of simply blanking it.
  //
  // Reads the SAME passive cardReshuffles() matched (first ungated one), so
  // the two can never disagree about which printing is in force.
  function reshuffleEffect(cardId, cardDefs) {
    const ps = cardDefs[cardId]?.effects_json?.passives;
    if (!ps) return null;
    for (const p of ps) {
      if (p.type !== 'reshuffle' || p.requires) continue;
      return (p.effect && p.effect.type) ? p.effect : null;
    }
    return null;
  }

  // A reshuffle effect can cause another card to arrive in a discard pile
  // (mill, discard, a sacrifice), and that card may itself reshuffle -- so
  // this path can re-enter itself with no budget anywhere above it. Capped per
  // card id per player per turn, reusing the per-player map v4.27 already
  // clears in the per-turn sweep: a fresh counter would need its own reset in
  // every engine and both of JS/Go's reset paths, which is precisely the shape
  // of bug the fake-card sweeps spent rounds chasing.
  //
  // 50 is a safety valve, not a balance lever: the most copies a deck can
  // legally hold is 15 for a common (100 only for infinite rarity), so no
  // honest board reaches it -- only a loop does. applyEffect's existing depth
  // guard already stops infinite RECURSION; this bounds a wide chain too, and
  // says so in the log instead of truncating silently.
  const RESHUFFLE_FX_PER_TURN = 50;

  // v4.23: on_recycle / on_opponent_recycle. Fires wherever cards actually
  // move from a discard pile back into a library -- every effect whose card
  // text reads "Recycle" (recycle_self/_opponent/_and, the Recycle additional
  // cost and shuffle_discard_to_library, which prints as Recycle on 55 cards)
  // plus the Reshuffle passive. kkoechel's call 2026-10-13: anything that puts
  // cards back in the library counts.
  //
  // `pl` is the player whose LIBRARY grew, following on_mill's convention that
  // the pair names who the event happened to, not who caused it -- so
  // recycle_opponent fires on_recycle on the opponent and on_opponent_recycle
  // on the caster.
  //
  // firePassives only reaches applyEffect when a matching passive exists, so
  // these calls consume no RNG while no card carries the new type: every
  // existing match replays bit-identically.
  function fireRecycle(pl, foe, rand, cardDefs, log, fromPassive, plPlan, foePlan) {
    if (!foe) return;
    firePassives(pl,  'on_recycle',          pl,  foe, rand, cardDefs, log, fromPassive, plPlan, foePlan);
    firePassives(foe, 'on_opponent_recycle', foe, pl,  rand, cardDefs, log, fromPassive, foePlan, plPlan);
  }

  function arriveInDiscard(pl, cardId, cardDefs, rand, log, cause, foe, plPlan, foePlan) {
    // Reshuffle replaces where the card LANDS; it does not cancel the event.
    // on_discard still fires, so one card can both send itself back to the
    // deck and sweep the rest of the discard with it. An earlier version
    // returned here, which made the two mutually exclusive and blocked
    // exactly that card (reported 2026-10-11).
    let landed = true;
    // Both reshuffle routes -- this card bouncing back, and the sweep that
    // fireOnDiscard may run -- end with cards in the library, so one length
    // check downstream covers both without threading context into either.
    const deckBefore = pl.deck.length;
    if (cardReshuffles(cardId, cardDefs)) {
      // Same primitive the recycle effect uses, so the two stay consistent
      // and consume the RNG identically across all four engines.
      pl.deck = shuffle([...pl.deck, cardId], rand);
      if (log) log.push(`  ${cardDefs[cardId]?.name || cardId} reshuffled into its owner's library instead of the discard (${pl.deck.length} in library)`);
      landed = false;
      // Fires AFTER the card is in the library, so an effect that counts the
      // deck sees the card it just put there. `foe` is required: without the
      // opposing player there is no valid two-player context to resolve into,
      // and the watcher block below already declines for the same reason.
      const rfx = reshuffleEffect(cardId, cardDefs);
      if (rfx && foe) {
        pl.mptShared = pl.mptShared || {};
        const rk = 'rsx:' + cardId;
        const used = pl.mptShared[rk] || 0;
        if (used < RESHUFFLE_FX_PER_TURN) {
          pl.mptShared[rk] = used + 1;
          // source is null on purpose -- the card is in the library now, not
          // on the board, so there is no board entry for it to point at.
          applyEffect(rfx, pl, foe, rand, cardDefs, log, plPlan, true, null, foePlan);
        } else if (log) {
          log.push(`  ${cardDefs[cardId]?.name || cardId} reshuffle effect capped for this turn (${RESHUFFLE_FX_PER_TURN})`);
        }
      }
    } else {
      pl.discard.push(cardId);
    }
    // CHOKEPOINT: every card reaching any discard pile passes through here.
    // Only 'hand' and 'cost' count as DISCARDED: 'deck' is milling (that is
    // cards_milled), 'play' is a permanent leaving (permanents_died), and a
    // resolved 'spell' or a 'tutor' fetching to the pile is not a discard in
    // any sense a player would recognise.
    if (cause === 'hand' || cause === 'cost') tally(pl, 'cards_discarded', 1);
    fireOnDiscard(pl, cardId, cardDefs, rand, log, landed, cause, foe, plPlan, foePlan);
    // Watchers: "a card of MINE was discarded" / "one of THEIRS was". Unlike
    // the card's own on_discard trigger these live on cards already in play,
    // so they need the full ap/op/plan context -- and `foe` plus the two
    // plans must be the pair that MATCHES pl, because at an opponent-facing
    // arrival (mill_opponent, discard_opponent) the arriving player is op and
    // the whole trio flips. Getting that backwards fires the wrong player's
    // watchers with no error, which is why every call site was mapped by hand
    // rather than blanket-rewritten.
    if (foe) {
      firePassives(pl,  'on_discard',          pl,  foe, rand, cardDefs, log, false, plPlan, foePlan);
      firePassives(foe, 'on_opponent_discard', foe, pl,  rand, cardDefs, log, false, foePlan, plPlan);
    }
    if (pl.deck.length > deckBefore) {
      fireRecycle(pl, foe, rand, cardDefs, log, false, plPlan, foePlan);
    }
  }

  // on_discard (v4.11): fires when this card actually LANDS in its owner's
  // discard pile, from any route. It does not fire when `reshuffle` replaced
  // the arrival -- the card never went to the discard, so nothing happened to
  // trigger on.
  //
  // Deliberately restricted to recycle_self rather than being a general event.
  // A general event would need ap/op/plan/opPlan at all ~15 arrival sites;
  // this effect only ever touches the owner's own discard and deck, which is
  // what keeps the arrival hook's signature small. Reuses discardPoolFor()
  // (so only_tags/exclude_tags/target_supertype behave exactly as they do on
  // recycle_self) and the same two-shuffle shape as recycle_opponent, so the
  // RNG is consumed identically in all four engines.
  //
  // The pick is BLIND -- no cost_preference steering, matching the engine's
  // existing convention that a pick with no battle plan behind it is random.
  // Shared by the Reshuffle passive's `sweep` and the on_discard trigger, so
  // the two cannot drift. `eff` supplies amount + only_tags/exclude_tags/
  // target_supertype exactly as recycle_self reads them.
  function sweepDiscardIntoDeck(pl, cardId, eff, cardDefs, rand, log, landed) {
    // include_self: the arriving card is normally excluded from its own
    // recycle (that is the "N OTHER cards" rule), which is why an on_discard
    // sweep leaves the card behind. Setting it lets the card come back with
    // everything else -- "when milled, return myself AND the pile", which was
    // otherwise inexpressible: the Reshuffle passive returns the card but has
    // no route filter, and the trigger has a route filter but never returned
    // the card.
    const selfIdx = (landed && !eff.include_self) ? pl.discard.lastIndexOf(cardId) : -1;
    const others  = selfIdx === -1 ? pl.discard.slice()
                                   : pl.discard.filter((_, i) => i !== selfIdx);
    const pool    = discardPoolFor(eff, others, cardDefs);
    const n = Math.min(
      literalZeroMeansAll(eff) ? pool.length
                               : Math.min(Math.max(Math.floor(eff.amount) || 1, 1), 10),
      pool.length);
    if (n <= 0) return;
    const shuf   = shuffle(pool, rand);
    const picked = shuf.slice(0, n);
    const rem = pl.discard.slice();
    for (const cid of picked) rem.splice(rem.indexOf(cid), 1);
    pl.discard = rem;
    pl.deck = shuffle([...pl.deck, ...picked], rand);
    // CHOKEPOINT for the sweep form of a recycle: the Reshuffle passive's
    // sweep and recycle_self's on_discard trigger both land here, and both
    // move cards from a discard pile back into a library.
    tally(pl, 'cards_recycled', n);
    if (log) log.push(`  ${cardDefs[cardId]?.name || cardId} shuffled ${n} card${n !== 1 ? 's' : ''} from the discard back into its owner's library (${pl.deck.length} in library)`);
  }

  // The Reshuffle passive's optional sweep: absent -> this card only,
  // sweep:'n' + amount -> this card plus N others, sweep:'all' -> this card
  // plus the whole (optionally tag-filtered) pile. A STRING rather than
  // amount=0, because Go cannot tell a missing amount from a literal 0.
  function reshuffleSweep(cardId, cardDefs) {
    const ps = cardDefs[cardId]?.effects_json?.passives;
    if (!ps) return null;
    for (const p of ps) {
      if (p.type !== 'reshuffle' || p.requires) continue;
      const sw = p.sweep;
      if (sw !== 'n' && sw !== 'all') continue;
      // The passive's existing singular `tag` field doubles as the filter, so
      // the editor reuses its tag input instead of growing a second one.
      const only = p.only_tags && p.only_tags.length ? p.only_tags
                 : (p.tag ? [p.tag] : undefined);
      return { type: 'recycle_self',
               amount: sw === 'all' ? 0 : (Math.floor(p.amount) || 1),
               only_tags: only, exclude_tags: p.exclude_tags,
               target_supertype: p.target_supertype };
    }
    return null;
  }

  // `cause` says HOW the card got here, so a trigger can ask for one route:
  // play (left the board) / hand (discarded from a hand) / deck (milled) /
  // cost (discarded to pay a cost) / spell (a spell resolved) / tutor (a
  // search put it straight into the pile). No `from` = every route, which is
  // what every existing on_discard card expects. The Reshuffle PASSIVE is
  // never gated: the keyword means "this never sits in a discard pile".
  // v4.35 play_self's budget, modelled exactly on RESHUFFLE_FX_PER_TURN above
  // and stored in the SAME per-player map the per-turn sweep already clears --
  // a fresh counter would need its own reset in four engines and both of
  // JS/Go's reset paths, which is precisely the shape of bug the fake-card
  // sweeps spent rounds chasing. A milled card that plays itself can mill or
  // draw, which can mill another copy, so this path can re-enter itself with
  // no budget anywhere above it. 50 is a safety valve, not a balance lever.
  const PLAY_SELF_PER_TURN = 50;

  // v4.43 the self-sacrifice rule. UNLIKE RESHUFFLE_FX_PER_TURN and
  // PLAY_SELF_PER_TURN above, this one IS a balance lever rather than a safety
  // valve, and it is a GLOBAL GAME RULE rather than a field on a card: a
  // permanent carrying a listed tag may sacrifice ITSELF at most N times per
  // player per turn. Copies past the budget simply stay on the board -- which
  // is the point, since they remain relics for every other card to count,
  // sacrifice or care about, and only their own payout is delayed.
  //
  // Keyed by TAG, not card id, so a second book card is covered the day it is
  // printed instead of being handed a budget of its own. 'book' is on exactly
  // one live card today (865 Book), so card-id keying would be
  // indistinguishable now and wrong later.
  //
  // An ORDERED list, not an object, because the lookup takes the FIRST listed
  // tag the card carries. Iterating the card's own tag array instead would let
  // payload tag order decide the winner, and would desync the four engines the
  // moment one card carried two listed tags.
  //
  // Scope is deliberately NARROW (kkoechel's call): a SELF-sacrifice only, so
  // another card that sacrifices a relic of its own choosing is never throttled
  // for having picked a Book. The budget lives in the SAME per-player map the
  // per-turn sweep already clears, for the reason PLAY_SELF_PER_TURN gives.
  const SELF_SAC_PER_TURN = [['book', 1]];

  // [tag, cap] for the first SELF_SAC_PER_TURN tag this card carries, else null.
  function selfSacRule(cardDefs, cardId) {
    const tags = cardDefs[cardId]?.tags;
    if (!tags || !tags.length) return null;
    for (const r of SELF_SAC_PER_TURN) if (tags.includes(r[0])) return r;
    return null;
  }

  function fireOnDiscard(pl, cardId, cardDefs, rand, log, landed, cause, foe, plPlan, foePlan) {
    const sweep = reshuffleSweep(cardId, cardDefs);
    if (sweep) sweepDiscardIntoDeck(pl, cardId, sweep, cardDefs, rand, log, landed);
    const trigs = cardDefs[cardId]?.effects_json?.triggers;
    if (!trigs) return;
    for (const t of trigs) {
      if (!t || t.event !== 'on_discard') continue;
      const eff = t.effect;
      if (!eff) continue;
      // Route filter: a trigger may ask for one cause only (e.g. from:'deck'
      // = only when milled). No `from` means every route, as before.
      if (t.from && t.from !== cause) continue;
      if (eff.type === 'play_self') {
        // v4.35. "When this is milled, play it" -- and, for free, every other
        // route the filter can name: from:'hand' is a Madness-style discard
        // outlet with no extra engine work.
        //
        // Only when it actually LANDED. A Reshuffle printing sends the card to
        // the library instead of the pile, so there is nothing sitting in a
        // discard to play; reaching into the deck to pull it back out would be
        // a different effect wearing this one's name.
        if (!landed || !foe) continue;
        playFromDiscard(pl, cardId, cardDefs, rand, log, foe, plPlan, foePlan);
        continue;
      }
      if (eff.type === 'recycle_self') {
        // "N OTHER cards": the instance that just arrived is not a candidate to
        // send back, or the card would endlessly recycle itself.
        // Only exclude "self" when the card actually LANDED. After a Reshuffle
        // it is not in the pile at all, and excluding by id would wrongly drop
        // a DIFFERENT copy that was legitimately sitting in the discard.
        sweepDiscardIntoDeck(pl, cardId, eff, cardDefs, rand, log, landed);
        continue;
      }
      // v4.56: every other effect resolves like any trigger's. Until now these
      // two were the only ones handled and the rest were dropped without a
      // word: Living Hill (#1321, "When this is milled: Summon a token copy of
      // a random creature costing 1 or less from your library") did nothing,
      // reported 2026-11-03. Same gate as play_self -- only when it LANDED
      // (a Reshuffle means nothing reached the discard) and with a foe to
      // resolve against. No source: the card is in the pile, not on a board.
      if (!landed || !foe) continue;
      // Label the lines with the card's OWNER. Unlabelled, they inherited
      // whatever trigger caused the discard -- a Wood-Fruit Tree destroyed by
      // the opponent's start-of-turn effect logged its own Wood Plank as
      // "[each_turn] player2: created a token copy of Wood Plank", and both
      // replay viewers credited the opponent (2026-11-14). Lines an inner
      // trigger already labelled pass through, as in fireTriggerOn.
      const who = (pl.pIndex === 0 || pl.pIndex === 1) ? `player${pl.pIndex + 1}` : null;
      if (!log || !who) { applyEffect(eff, pl, foe, rand, cardDefs, log, plPlan, false, null, foePlan); continue; }
      const sub = [];
      sub.__src = log.__src;
      applyEffect(eff, pl, foe, rand, cardDefs, sub, plPlan, false, null, foePlan);
      for (const l of sub) {
        const t2 = String(l).trim();
        log.push(t2.startsWith('§src:') || t2.startsWith('[on_') || t2.startsWith('[chapter]') ? l : `  [on_discard] ${who}: ${t2}`);
      }
      if (sub.__src !== undefined) log.__src = sub.__src;
    }
  }

  // v4.35. Plays a card that is sitting in its owner's discard pile, for
  // FREE -- no energy, no additional costs (kkoechel's call: a windfall is the
  // point, and the cost of a card you did not choose to draw is a balance
  // lever on the card, not a rule).
  //
  // A spell's whole body IS its on_enter trigger (234 of 234 spell events), so
  // "playing" one is the same four steps playOneCard takes: fire on_enter,
  // fire both tag-watcher families, and land it in the discard. A permanent
  // enters as a NORMAL entry -- NOT Gravecast's token copy -- because a
  // manifested or milled card is a real card and must go to the discard when
  // it later leaves play.
  //
  // Re-entry is self-limiting even before the budget: the card lands again
  // with cause 'spell', so a from:'deck' trigger cannot fire off its own
  // resolution.
  function playFromDiscard(pl, cardId, cardDefs, rand, log, foe, plPlan, foePlan) {
    pl.mptShared = pl.mptShared || {};
    const key = 'psx:' + cardId;
    const used = pl.mptShared[key] || 0;
    if (used >= PLAY_SELF_PER_TURN) {
      if (log) log.push(`  ${cardDefs[cardId]?.name || cardId} play-from-discard capped for this turn (${PLAY_SELF_PER_TURN})`);
      return;
    }
    pl.mptShared[key] = used + 1;
    // A free play off the discard is still a card played.
    tally(pl, 'cards_played', 1);
    // Leave the pile first, so an effect that counts or sweeps the discard
    // does not see the card it is currently resolving.
    const idx = pl.discard.lastIndexOf(cardId);
    if (idx === -1) return;
    pl.discard.splice(idx, 1);
    const def = cardDefs[cardId];
    if (log) log.push(`  played ${def?.name || cardId} [${def?.supertype || 'spell'}] from the discard (free)`);
    if (isPermanent(def)) {
      reanimateOne(pl, foe, cardId, cardDefs, rand, log, 'the discard', false, plPlan, foePlan);
      return;
    }
    const shell = { card_id: cardId };
    fireTriggerOn(shell, 'on_enter', pl, foe, rand, cardDefs, log, plPlan, foePlan);
    fireTagWatchers(pl, foe, pl, shell, 'on_tag_played', rand, cardDefs, log, plPlan, foePlan);
    fireTagWatchers(foe, pl, pl, shell, 'on_opponent_tag_played', rand, cardDefs, log, foePlan, plPlan);
    arriveInDiscard(pl, cardId, cardDefs, rand, log, 'spell', foe, plPlan, foePlan);
  }

  function toDiscardOrExile(pl, entry, cardDefs, rand, log, foe, plPlan, foePlan) {
    // CHOKEPOINT: a permanent that left play for the pile (or vanished, if it
    // was a token). Counted BEFORE the isToken return -- a token death is still
    // a death, and tallying after that return would miss every one of them
    // silently, in all four engines identically, which no parity check sees.
    // A BOUNCE does not come through here, which is correct: a permanent
    // returned to hand did not die.
    tally(pl, 'permanents_died', 1);
    if (entry.isToken) return;
    arriveInDiscard(pl, entry.card_id, cardDefs, rand, log, 'play', foe, plPlan, foePlan);
  }
  // ── Tagged counters (v4.36) ───────────────────────────────────────────────
  // A permanent accumulates named counters -- "put a time counter on this" --
  // which later cards SPEND as a cost or READ as an amount. They live on the
  // board entry and die with it: there is no player-level pool, so a permanent
  // that leaves play takes its counters with it (kkoechel's call).
  //
  // Created lazily, so an entry that never gains one carries nothing and the
  // replay snapshot stays the size it was.
  //
  // NEVER cleared by the per-turn sweep. That sweep clears loopCounts and
  // mptShared, both of which sit on the same entry and are both per-TURN
  // budgets; these are not. A counter wiped every turn looks like a balance
  // problem ("it never reaches 5"), not a bug. Mute does not touch them
  // either: game/rules.php's Mute entry already promises "counters stay".
  const MAX_COUNTERS_PER_ENTRY = 100;   // a clamp against a runaway loop, not a balance lever

  function addCounters(entry, tag, n) {
    if (!entry || !tag || n <= 0) return 0;
    entry.counters = entry.counters || {};
    const before = entry.counters[tag] || 0;
    const after  = Math.min(before + n, MAX_COUNTERS_PER_ENTRY);
    entry.counters[tag] = after;
    return after - before;
  }

  function countCounters(p, tag) {
    let n = 0;
    for (const e of p.board) n += (e.counters && e.counters[tag]) || 0;
    return n;
  }

  // All-or-nothing, exactly like utilize_and/sacrifice_and: short of the full
  // cost, NOTHING is spent and the effect does not happen. Spends from the
  // permanent holding the MOST first so a board of several never strands a
  // pile one short, ties breaking on iid -- deterministic, no RNG, because
  // this is a cost and not a choice the battle plan should steer.
  function spendCounters(p, tag, n, selfOnly, source) {
    const pool = (selfOnly ? (source ? [source] : []) : p.board)
      .filter(e => e && !e.destroyed && e.counters && e.counters[tag] > 0);
    let total = 0;
    for (const e of pool) total += e.counters[tag];
    if (total < n) return false;
    pool.sort((a, b) => (b.counters[tag] - a.counters[tag]) || (a.iid - b.iid));
    let left = n;
    for (const e of pool) {
      const take = Math.min(left, e.counters[tag]);
      e.counters[tag] -= take;
      if (e.counters[tag] === 0) delete e.counters[tag];
      left -= take;
      if (left === 0) break;
    }
    return true;
  }

  function toHandOrExile(pl, entry) {
    if (entry.isToken) return;
    pl.hand.push(entry.card_id);
  }

  // v4.33 manifest_card and v4.34 shuffle_permanent_into_deck both put a card
  // into a library at a chosen DEPTH, which nothing else in the engine does --
  // every other deck insert reshuffles the whole library. Factored into one
  // place per engine so the band arithmetic and its RNG cost cannot drift
  // between the two callers: band N = a uniformly random index in
  // [0, min(N, len)], band 1 = on top, band 0/absent = a full reshuffle.
  // EXACTLY ONE rand() per copy for a banded insert, len-1 for a reshuffle.
  function insertIntoDeck(pl, cardId, band, rand) {
    if (band > 0) {
      const idx = Math.floor(rand() * Math.min(band, pl.deck.length + 1));
      pl.deck.splice(idx, 0, cardId);
    } else {
      pl.deck = shuffle([...pl.deck, cardId], rand);
    }
  }

  // v4.39. Put N copies of one card into a library, for exile_deck_and_manifest.
  //
  // THE RNG RULE, and the reason this is a function rather than a loop at the
  // call site. A banded insert costs exactly one rand() per copy, so the band>0
  // arm is just insertIntoDeck N times. The band-0 arm is NOT: insertIntoDeck
  // reshuffles the WHOLE library per card, so N calls would be N reshuffles --
  // ~800 rand() calls for a 40-card library, O(n^2) work, and four engines each
  // having to reproduce that exact sequence. One shuffle of the finished list
  // costs len-1 calls, is the same thing a player would do, and is trivially
  // identical in Go and Python.
  function refillDeck(pl, cardId, n, band, rand) {
    if (band > 0) {
      for (let i = 0; i < n; i++) insertIntoDeck(pl, cardId, band, rand);
    } else {
      const add = [];
      for (let i = 0; i < n; i++) add.push(cardId);
      pl.deck = shuffle([...pl.deck, ...add], rand);
    }
  }

  // The deck twin of toHandOrExile/toDiscardOrExile. A token entry still
  // vanishes: a token copy must never become a real card sitting in a library.
  function toDeckOrExile(pl, entry, band, rand) {
    if (entry.isToken) return false;
    insertIntoDeck(pl, entry.card_id, band, rand);
    return true;
  }

  // fireLeaveTrigger fires on_leave (forwarding isBounce), plus on_death for
  // any non-bounce departure -- matching game/rules.php's glossary ("On
  // Death... killed, destroyed, or sacrificed... a card bounced back to hand
  // did not die, so only On Leave fires for it, not On Death"). Tokens
  // (conjured by create_token_copy) fire on_death even on bounce, since they
  // can never actually sit in a hand -- toHandOrExile silently drops them
  // instead, so for a token ANY departure is its "death".
  //
  // plan/opPlan (v3.42, appended after isBounce to avoid disturbing the two
  // call sites that pass isBounce=true positionally): the caller is
  // responsible for passing these already correctly matched to ap/op --
  // i.e. if ap/op are swapped at the call site (bc belongs to the player
  // passed as op elsewhere), plan/opPlan must be swapped identically.
  function fireLeaveTrigger(bc, ap, op, rand, cardDefs, log, isBounce, plan, opPlan) {
    if (bc.isToken || !isBounce) fireTriggerOn(bc, 'on_death', ap, op, rand, cardDefs, log, plan, opPlan);
    fireTriggerOn(bc, 'on_leave', ap, op, rand, cardDefs, log, plan, opPlan, isBounce);
  }

  function tryPlayChampion(ap, op, cardDefs, rand, iid, log, chaosCounts, plan, opPlan) {
    if (!ap.championInZone) return false;
    const cid = ap.championId;
    const def = cardDefs[cid];
    if (!def) return false;
    const cost = (def.cost ?? 0) + ap.championCostInc;
    if (plan?.champion_timing === 'hold') {
      const threshold = plan?.champion_energy_threshold ?? 0;
      if ((ap.energy + ap.ephemeral) < threshold) return false;
    }
    if (!canAfford(ap, cost, def)) return false;
    // v4.41. Checked AFTER affordability, as playOneCard's chain is, so a
    // champion that is merely unaffordable never reports a gate instead.
    // tryPlayChampion is called from a `while` loop, so the message reuses
    // playOneCard's once-per-card-per-turn budget rather than repeating every
    // pass; `log &&` keeps the fast turn loop silent.
    // v4.41: a champion must ALSO pay its card's additional costs (owner's
    // call, 2026-10-18). tryPlayChampion previously checked affordability and
    // nothing else, so "To Play: Sacrifice a relic" was free for a champion --
    // and a champion is recast repeatedly, so a free cost compounds. Exactly
    // one live card is affected: The Tin Butcher (763).
    //
    // Same predicates in the same short-circuit order as playOneCard, so a
    // card behaves identically whether it is cast from hand or from the
    // champion zone. The gate is checked first because it is what the card
    // prints first.
    const ac = def?.effects_json?.additional_cost;
    const champBlock =
        playGateBlocker(def, ap, op, cardDefs)
     ?? (!canFulfillUtilizeCost(ap, cardDefs, def)   ? 'needs a permanent to utilize'
      :  !canFulfillSacrificeCost(ap, cardDefs, def) ? 'needs a permanent to sacrifice'
      :  !canFulfillHonorCost(ap, def)               ? `needs ${ac.honor} honor (has ${ap.honor})`
      :  !canFulfillDiscardCost(ap, def, false)      ? 'needs cards in hand to discard'
      :  !canFulfillLifeCost(ap, def)                ? `needs ${ac.life} life (has ${ap.life})`
      :  !canFulfillShieldsCost(ap, def)             ? `needs ${ac.shields} shields (has ${ap.shields})`
      :  !canFulfillRecycleCost(ap, def)             ? 'needs cards in the discard to recycle'
      :  !canFulfillExileDiscardCost(ap, def)        ? 'needs cards in the discard to exile'
      :  null);
    if (champBlock) {
      if (log && ap.blockedLogged && !ap.blockedLogged.has(cid)) {
        ap.blockedLogged.add(cid);
        log.push(`  could not play Champion ${def.name || cid}: ${champBlock}`);
      }
      return false;
    }
    spendEnergy(ap, cost, def);
    tally(ap, 'cards_played', 1);
    // Paid AFTER the energy, as playOneCard does. fulfillDiscardCost's
    // excludeIdx is -1: there is no hand slot to protect for a champion.
    // Cost lines print after "played Champion X", as playOneCard's do.
    const costLog = log ? [] : null;
    if (ac?.utilize)       fulfillUtilizeCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
    if (ac?.sacrifice)     fulfillSacrificeCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
    if (ac?.honor)         fulfillHonorCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
    if (ac?.discard)       fulfillDiscardCost(ap, op, cardDefs, def, -1, rand, costLog, plan, opPlan);
    if (ac?.life)          fulfillLifeCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
    if (ac?.shields)       fulfillShieldsCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
    if (ac?.recycle)       fulfillRecycleCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
    if (ac?.exile_discard) fulfillExileDiscardCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
    // v4.48: a champion takes its supplement too (owner's call 2026-10-24),
    // consistent with v4.41's rule that a champion satisfies To Play gates and
    // pays additional costs. Both Go engines and validate.py hook both sites;
    // this one was missing here, which is exactly the kind of single-engine
    // gap a parity run only finds once a card actually uses it.
    const supDeferred = canPaySupplement(ap, def, cardDefs, plan) ? paySupplement(ap, op, cardDefs, def, rand, costLog, plan, opPlan) : null;
    ap.championInZone = false;
    const entry = {
      owner:    ap,
      card_id:  cid,
      hp:       canBeDamaged(def) ? def.stats_json.health : null,
      armor:    def.stats_json?.armor ?? 0,
      // armor is this permanent's STANDING contribution to totalOpArmor and is
      // read whether or not it can be damaged. curArmor is the per-entry
      // ABSORPTION POOL, gated on canBeDamaged to match validate.go
      // (`if cd.canDmg`) and validate.py (`_base_armor if _can_dmg else 0`,
      // whose comment says it "stays gated, matching Go"). JS set it
      // unconditionally -- the only engine that did.
      //
      // Latent, not live: reachable only for a champion that cannot be
      // damaged, and curArmor is read by the most_armor/least_armor target
      // preferences. Champions must be creatures and creatures carry health,
      // so nothing reaches it today. Aligned anyway -- the last latent
      // champion mismatch (the melee-0 honor link) cost a day.
      curArmor: canBeDamaged(def) ? (def.stats_json?.armor ?? 0) : 0,
      melee:    def.stats_json?.melee ?? 0,
      utilized: false,
      // v4.19 Stun. stunCounters = how many of the owner's FUTURE untaps this
      // entry skips; stunned = this tap came from a stun rather than from its
      // owner paying a cost. Two fields, not one: Stun 0 taps with zero
      // counters, so `stunCounters > 0` cannot answer "is it stunned".
      stunCounters: 0,
      stunned:  false,
      // v4.22 Mute. muteCounters = how many of the owner's FUTURE turn-starts this
      // entry stays blank for; mutePermanent = Mute 0, blank until it leaves play.
      // Separate fields because Mute 0 is the PERMANENT case (unlike Stun 0, which
      // is the shortest one), so `muteCounters > 0` cannot answer "is it muted" --
      // isMuted() is the only thing that may.
      muted: false,
      muteCounters: 0,
      mutePermanent: false,
      loopCounts: {},
      iid:      ++iid.val
    };
    let chaosNote = '';
    const armorN = chaosCounts?.permanents_enter_armored || 0;
    if (armorN > 0) { entry.armor += armorN; entry.curArmor += armorN; chaosNote = ` (chaos: +${armorN} armor)`; }
    const meleeN = chaosCounts?.creatures_enter_melee_boosted || 0;
    if (meleeN > 0 &&
        (def.supertype || '').toLowerCase().includes('creature') && !hasCardPassive(def, 'melee_locked')) { entry.melee += meleeN; chaosNote = ` (chaos: +${meleeN} melee)`; }
    entry.curArmor += anthemBonus(cid, ap.board, cardDefs, 'anthem_armor', null);
    ap.board.push(entry);
    if (log) log.push(`  played Champion ${def.name || cid} cost ${cost}${chaosNote}`);
    if (costLog) log.push(...costLog);
    if (supDeferred) applyEffect(supDeferred, ap, op, rand, cardDefs, log, plan, undefined, entry, opPlan);   // v4.54
    fireTriggerOn(entry, 'on_enter', ap, op, rand, cardDefs, log, plan, opPlan);
    fireTagWatchers(ap, op, ap, entry, 'on_tag_played', rand, cardDefs, log, plan, opPlan);
    fireTagWatchers(op, ap, ap, entry, 'on_opponent_tag_played', rand, cardDefs, log, opPlan, plan);
    return true;
  }

  // ── Energy helpers ───────────────────────────────────────────────────────────
  // restrictedEnergy entries only count toward `cost` when `def` matches their
  // tag (if set) or target_supertype (default 'creature') -- same either/or
  // matcher as cost_modifier/anthem. `def` is omitted at call sites that don't
  // represent playing a specific card (there are none today, but canAfford/
  // spendEnergy stay safe if def is undefined: matches nothing, so restricted
  // pools simply don't apply).
  // v4.60: excludeSupertypes, when set, wins over tag/targetSupertype -- the
  // energy pays for any card that is NONE of the listed supertypes.
  // v4.64 Patient (Ryan, 2026-11-14): a card with the printed `patient`
  // passive cannot be paid for with anything that expires at end of turn --
  // the ephemeral pool AND ephemeral restricted-energy entries -- for its play
  // cost and any supplement alike. Printed only: a card in hand is not on the
  // board, so no anthem can grant it and Mute cannot strip it.
  function isPatient(def) {
    const ps = def?.effects_json?.passives;
    return Array.isArray(ps) && ps.some(p => p && p.type === 'patient');
  }
  function ephemeralFor(p, def) { return isPatient(def) ? 0 : p.ephemeral; }

  function restrictedEnergyMatch(entry, def) {
    if (entry.ephemeral && isPatient(def)) return false;   // v4.64 Patient
    if (entry.excludeSupertypes && entry.excludeSupertypes.length) {
      if (!def) return false;
      return !entry.excludeSupertypes.some(t => matchesSupertype(def, t));
    }
    return entry.tag
      ? (def?.tags ?? []).includes(entry.tag)
      : matchesSupertype(def, entry.targetSupertype || 'creature');
  }

  // v4.60 spend order (Ryan): narrowest first, ephemeral before persistent,
  // then the order gained. Breadth = how many of the five supertypes an entry
  // can pay for. Identical to validate.go's restrictedEnergyBreadth.
  const RE_SUPERTYPES = ['creature', 'relic', 'structure', 'enchantment', 'spell'];
  function restrictedEnergyBreadth(entry) {
    if (entry.excludeSupertypes && entry.excludeSupertypes.length) {
      const seen = new Set();
      for (const raw of entry.excludeSupertypes) {
        const t = String(raw).toLowerCase();
        if (t === 'permanent') return 0;
        if (RE_SUPERTYPES.includes(t)) seen.add(t);
      }
      return 5 - seen.size;
    }
    if (entry.tag) return RE_SUPERTYPES.includes(String(entry.tag).toLowerCase()) ? 1 : 0;
    const filter = String(entry.targetSupertype || 'creature').toLowerCase();
    if (filter === 'permanent') return 5;
    return RE_SUPERTYPES.includes(filter) ? 1 : 0;
  }

  // Restricted energy this card may spend (v4.61: shared by canPaySupplement).
  function p_restrictedMatching(p, def) {
    return def ? p.restrictedEnergy.reduce((sum, e) => sum + (restrictedEnergyMatch(e, def) ? e.amount : 0), 0) : 0;
  }
  function canAfford(p, cost, def) {
    return p_restrictedMatching(p, def) + p.energy + ephemeralFor(p, def) >= cost;   // v4.64 Patient
  }

  function spendEnergy(p, cost, def) {
    let remaining = cost;
    if (def && p.restrictedEnergy.length) {
      const order = p.restrictedEnergy.map((e, i) => i).sort((a, b) => {
        const ea = p.restrictedEnergy[a], eb = p.restrictedEnergy[b];
        const ba = restrictedEnergyBreadth(ea), bb = restrictedEnergyBreadth(eb);
        if (ba !== bb) return ba - bb;
        if (!!ea.ephemeral !== !!eb.ephemeral) return ea.ephemeral ? -1 : 1;
        return a - b;
      });
      for (const i of order) {
        const e = p.restrictedEnergy[i];
        if (remaining <= 0) break;
        if (!restrictedEnergyMatch(e, def)) continue;
        const spend = Math.min(e.amount, remaining);
        e.amount -= spend;
        remaining -= spend;
      }
      p.restrictedEnergy = p.restrictedEnergy.filter(e => e.amount > 0);
    }
    const eph = Math.min(ephemeralFor(p, def), remaining);   // v4.64 Patient
    p.ephemeral -= eph;
    remaining -= eph;
    p.energy -= remaining;
    // CHOKEPOINT: every energy PAYMENT goes through here -- the play loop and
    // tryPlayChampion are its only two callers -- so energy_spent needs exactly
    // this one site. Deliberately NOT lose_energy/remove_opponent_energy: those
    // take energy FROM you, which is not energy you spent. `cost` rather than
    // the drained total, because restricted/ephemeral/basic are three buckets
    // paying one price.
    tally(p, 'energy_spent', cost);
  }

  // ── Draw ─────────────────────────────────────────────────────────────────────
  // v4.42. One place that records a per-turn metric. Ignores non-positive
  // amounts so a "gain" of 0 never opens a gate (the same rule the champion
  // honor link needed in v4.41: a gain of nothing is not a gain).
  function tally(p, metric, n) {
    if (!p || !(n > 0)) return;
    p.turnTally[metric] = (p.turnTally[metric] || 0) + n;
  }

  function tryDraw(p) {
    if (!p.deck.length) { p.deckedOut = true; return false; }
    p.hand.push(p.deck.shift());
    // CHOKEPOINT: every draw in this engine goes through here -- the turn's
    // own draw, every draw_card effect, chaos extras, drawlink and tutors to
    // hand -- so cards_drawn needs exactly this one site.
    tally(p, 'cards_drawn', 1);
    return true;
  }

  // ── Player damage ────────────────────────────────────────────────────────────
  // `cause` feeds the deck-testing breakdown ("what % of losses were poison?").
  // Recorded ONLY when life actually drops, so a fully-shielded hit does not
  // overwrite the real killer. This never reaches a submitted result: runCohort
  // builds its pushed object from named fields, and hashResults stringifies only
  // that -- see the note on simulateMatch's `games`.
  function dmgPlayer(p, amount, cause) {
    const blocked = Math.min(p.shields, amount);
    p.shields -= blocked;
    const lost = amount - blocked;
    p.life -= lost;
    if (lost > 0 && cause) p.lifeLossCause = cause;
    tally(p, 'life_lost', lost);
    tally(p, 'shields_lost', blocked);
    return lost;
  }

  // ── Creature damage (armor absorbs first) ────────────────────────────────────
  function dmgCreature(entry, amount) {
    const absorbed = Math.min(entry.curArmor, amount);
    entry.curArmor -= absorbed;
    entry.hp -= (amount - absorbed);
    return absorbed;
  }

  // ── Armor recovery (end of each turn) ────────────────────────────────────────
  // Uses entry.armor (set at placement, can be boosted by chaos) as the per-game base,
  // plus any live anthem_armor aura bonus from other permanents currently on the board.
  function recoverArmor(board, cardDefs) {
    for (const entry of board) {
      if (entry.hp !== null) {
        entry.curArmor = effectiveArmor(entry, board, cardDefs);
      }
    }
  }

  // ── Card type helpers ────────────────────────────────────────────────────────
  function isPermanent(def) {
    if (!def) return true;
    if (def.card_type === 'permanent')     return true;
    if (def.card_type === 'non_permanent') return false;
    // Infer: spell supertype → non-permanent
    const supertypes = (def.supertype || '').toLowerCase().trim().split(/\s+/);
    return !supertypes.includes('spell');
  }

  function canBeDamaged(def) {
    return def?.stats_json?.health != null;
  }

  // ── Reanimate ─────────────────────────────────────────────────────────────────
  // Builds a fresh board entry from a card_id and puts it into play -- same field
  // set and chaos-effect handling as playOneCard's hand-play constructor (this is
  // NOT a "restore", discard holds no old entry state to restore -- see
  // js/battle-engine.js's discard-push sites, which only ever store a bare
  // card_id). Fires on_enter (which fires on_tag_enter internally) but
  // deliberately NOT on_tag_played -- reanimating isn't "playing" the card from
  // hand. Uses the shared __iid counter directly (see its declaration above)
  // since this fires from inside applyEffect/firePassives, not from
  // playOneCard/tryPlayChampion, which receive iid as an explicit parameter.
  // Note: unlike playOneCard's hand-play constructor, this doesn't apply
  // permanents_enter_armored/creatures_enter_melee_boosted chaos-effect bonuses
  // -- `chaos` is a per-game local in _simSingleGame/recordBattle, not
  // accessible from inside applyEffect's scope (same issue __iid solves above,
  // not worth solving twice for a chaos-arena-only cosmetic bonus).
  //
  // plan/opPlan are the reanimating player's (ap's) own battle plan and the
  // opponent's, forwarded into on_enter the same way playOneCard already
  // does -- covers the entering card's own on_enter trigger effect (e.g.
  // kill_target/sacrifice_and reading target/discard preference),
  // on_tag_enter watchers on ap's own board, and (as of v3.42, since
  // applyEffect now carries opPlan) on_opponent_tag_enter watchers on op's
  // board too.
  // toOpp: the effect's controller put this onto its OPPONENT's board (a
  // side:"opponent" token or manifest). The log line says so, because its
  // "[event] playerN:" label names the controller, not the receiver, and both
  // replay viewers read that label as the side the card arrived on (2026-11-14).
  function reanimateOne(ap, op, cid, cardDefs, rand, log, fromZone, isToken, plan, opPlan, toOpp) {
    const def = cardDefs[cid];
    if (!def) return;
    const entry = {
      owner:    ap,
      card_id:  cid,
      hp:       canBeDamaged(def) ? def.stats_json.health : null,
      armor:    def.stats_json?.armor ?? 0,
      curArmor: def.stats_json?.armor ?? 0,
      melee:    def.stats_json?.melee ?? 0,
      utilized: false,
      // v4.19 Stun. stunCounters = how many of the owner's FUTURE untaps this
      // entry skips; stunned = this tap came from a stun rather than from its
      // owner paying a cost. Two fields, not one: Stun 0 taps with zero
      // counters, so `stunCounters > 0` cannot answer "is it stunned".
      stunCounters: 0,
      stunned:  false,
      // v4.22 Mute. muteCounters = how many of the owner's FUTURE turn-starts this
      // entry stays blank for; mutePermanent = Mute 0, blank until it leaves play.
      // Separate fields because Mute 0 is the PERMANENT case (unlike Stun 0, which
      // is the shortest one), so `muteCounters > 0` cannot answer "is it muted" --
      // isMuted() is the only thing that may.
      muted: false,
      muteCounters: 0,
      mutePermanent: false,
      loopCounts: {},
      iid:      ++__iid.val,
      isToken:  !!isToken
    };
    entry.curArmor += anthemBonus(cid, ap.board, cardDefs, 'anthem_armor', null);
    ap.board.push(entry);
    const ctl = toOpp ? " under opponent's control" : '';
    if (log) log.push(isToken
      ? `  created a token copy of ${def.name || cid} [${def.supertype || 'permanent'}]${ctl}`
      : `  reanimated ${def.name || cid} [${def.supertype || 'permanent'}] from ${fromZone || 'discard'}${ctl}`);
    fireTriggerOn(entry, 'on_enter', ap, op, rand, cardDefs, log, plan, opPlan);
  }

  // supertype is checked only when tag is falsy -- every existing caller
  // (reanimate_tagged/reanimate_random_tagged) passes a real tag and '' for
  // supertype, so they're unaffected by this parameter. animatelink (the
  // combat-link keyword) has no tag concept, so it passes tag='',
  // supertype='creature' instead. 2026-08-08.
  function reanimateCandidates(ap, cardDefs, tag, supertype, costOp, costThreshold) {
    return ap.discard.filter(cid => {
      if (cid === ap.championId) return false;
      const def = cardDefs[cid];
      if (!def || !isPermanent(def)) return false;
      if (tag) {
        if (!(def.tags ?? []).includes(tag)) return false;
      } else if (supertype) {
        if (!matchesSupertype(def, supertype)) return false;
      }
      if (costOp) {
        const cost = def.cost ?? 0;
        if (costOp === 'lt')  return cost <  costThreshold;
        if (costOp === 'lte') return cost <= costThreshold;
        if (costOp === 'gt')  return cost >  costThreshold;
        if (costOp === 'gte') return cost >= costThreshold;
        if (costOp === 'eq')  return cost === costThreshold;
      }
      return true;
    });
  }

  // ── Utilize helpers ───────────────────────────────────────────────────────────
  // Whether this effect's amount is a LITERAL 0 written on the card, rather
  // than a 0 a dynamic source happened to compute this turn. resolveAmount()
  // collapses both into one number, so "recycle cards equal to your [beast]
  // creatures" with an empty board would otherwise recycle the ENTIRE discard
  // pile at the exact moment it should do nothing. Mirrors validate.go's
  // literalZeroMeansAll and validate.py's literal_zero_means_all.
  function literalZeroMeansAll(eff) {
    return !eff.count_source && !eff.typeburst_source && (Math.floor(eff.amount) || 0) === 0;
  }

  // Ids in pile passing only_tags/exclude_tags/target_supertype -- the same
  // pool recycle_self builds. No filters set -> the whole pile.
  function discardPoolFor(eff, pile, cardDefs) {
    return pile.filter(cid =>
      !hasExcludedTag(cardDefs, cid, eff.exclude_tags)
      && !lacksRequiredTag(cardDefs, cid, eff.only_tags)
      && (!eff.target_supertype || matchesSupertype(cardDefs[cid], eff.target_supertype)));
  }

  // Remove exactly one entry per picked id -- not every copy.
  function removeOneEach(pile, picked) {
    const rem = pile.slice();
    for (const cid of picked) {
      const i = rem.indexOf(cid);
      if (i !== -1) rem.splice(i, 1);
    }
    return rem;
  }

  function matchesSupertype(def, targetType) {
    if (targetType === 'permanent') return true;
    return (def?.supertype || '').toLowerCase().trim().split(/\s+/).includes(targetType.toLowerCase());
  }

  // See validate.go's grantableKeywords: boolean flags only.
  const GRANTABLE_KEYWORDS = new Set(['indestructible', 'untargetable', 'flying', 'shieldbreaker', 'reach']);   // v4.62 flying, v4.63 shieldbreaker (was piercing), v4.66 reach
  // Melee-scaled counterparts -- see validate.go's grantableScaledKeywords for
  // why these are a separate set from the boolean flags above.
  const GRANTABLE_SCALED_KEYWORDS = new Set(['lifelink']);

  // Mirrors validate.go's countZoneByTag. An EMPTY tag means "any card" in
  // every zone, and destroyed board entries are skipped.
  function countZoneByTag(p, tag, zone, cardDefs) {
    const match = cid => !tag || (cardDefs[cid]?.tags ?? []).includes(tag);
    if (zone === 'discard') return p.discard.filter(match).length;
    if (zone === 'hand')    return p.hand.filter(match).length;
    return p.board.filter(c => !c.destroyed && match(c.card_id)).length;
  }

  // Evaluates a printed passive's `requires` gate. Absent gate = always on.
  function requiresHolds(req, entry, cardDefs) {
    if (!req) return true;
    let side = entry.owner;
    if (req.side === 'opponent') side = side ? side.opponent : null;
    if (!side) return false;
    return countZoneByTag(side, req.tag || '', req.zone || 'board', cardDefs) >= (req.min || 1);
  }

  // How many sources on `board` confer kw on entry. Counted, not
  // short-circuited: a boolean keyword only needs one, but a melee-scaled one
  // stacks. A granter never confers on itself, mirroring anthemBonus.
  function keywordGrantsFrom(board, entry, kw, targetPlayer, cardDefs) {
    const def = cardDefs[entry.card_id];
    let n = 0;
    for (const src of board) {
      if (src === entry || src.destroyed) continue;
      const ps = activePassives(src, cardDefs);          // v4.22 mute chokepoint
      if (!ps) continue;
      for (const p of ps) {
        if (p.type !== 'anthem_keyword' || p.keyword !== kw) continue;
        if ((p.target_player || 'self') !== targetPlayer) continue;
        if (!requiresHolds(p.requires, src, cardDefs)) continue;
        // v4.67: a tag narrows the supertype filter, never replaces it.
        if (p.tag && !(def?.tags ?? []).includes(p.tag)) continue;
        if (matchesSupertype(def, p.target_supertype || 'creature')) n++;
      }
    }
    return n;
  }

  // Total anthem_keyword sources conferring kw on entry, across both boards.
  // Each grant is worth exactly 1 -- the granter's own amount is deliberately
  // not consulted, so a scaled keyword's value comes purely from the
  // RECIPIENT's link basis (see sumCombatKWScaled).
  function grantedKeywordCount(entry, kw, cardDefs) {
    const own = entry.owner;
    if (!own) return 0;
    let n = keywordGrantsFrom(own.board, entry, kw, 'self', cardDefs);
    if (own.opponent) n += keywordGrantsFrom(own.opponent.board, entry, kw, 'opponent', cardDefs);
    return n;
  }

  // Single answer to "does this entry have kw right now" -- printed (optionally
  // gated by `requires`), granted from its own board, or granted across by an
  // opponent-facing source. Recomputed per call, never cached.
  function entryHasKeyword(entry, kw, cardDefs, ignoreMute) {
    // v4.22 Mute: a muted entry stops printing its OWN keywords (so mute
    // strips untargetable, indestructible and unstunnable -- "blank" means
    // blank), but it still receives keywords GRANTED to it by other
    // permanents. Mute blanks what the card is, not what is done to it, so
    // only the printed branch below is skipped, never grantedKeywordCount.
    // ignoreMute is for isUnmutable alone; see the note there.
    const ps = (ignoreMute || !isMuted(entry))
      ? cardDefs[entry.card_id]?.effects_json?.passives
      : null;
    if (ps) {
      for (const p of ps) {
        if (p.type !== kw) continue;
        if (!p.requires) return true;       // an ungated printing wins outright
        if (requiresHolds(p.requires, entry, cardDefs)) return true;
      }
    }
    if (!entry.owner || !GRANTABLE_KEYWORDS.has(kw)) return false;
    return grantedKeywordCount(entry, kw, cardDefs) > 0;
  }

  function isUntargetable(entry, cardDefs) {
    return entryHasKeyword(entry, 'untargetable', cardDefs);
  }

  // Unlike untargetable (which removes an entry from selection pools entirely),
  // indestructible entries remain valid targets -- they can be selected/matched
  // by damage and destroy effects, but the effect itself is negated at the point
  // of application. Callers must check this explicitly at each damage/destroy
  // site rather than filtering pools, since "even if targeted" is the point.
  function isIndestructible(entry, cardDefs) {
    return entryHasKeyword(entry, 'indestructible', cardDefs);
  }

  // v4.19. Armor now uses the SAME !utilized predicate as melee does, and that
  // symmetry is the whole rule: a tapped permanent neither attacks nor blocks.
  // Until v4.19 melee filtered utilized attackers while armor counted every
  // permanent, so paying a utilize cost removed a body from your attack but
  // left it defending at full strength -- and because utilized clears at the
  // OWNER's turn start, a permanent tapped on your turn stayed tapped through
  // the opponent's entire turn and still blocked their attack.
  //
  // Measured before shipping (kkoechel chose this over a stun-only variant):
  // same decks, same seeds, one line different -- 0.9% of whole-catalog
  // matches differ and 0.4% flip a winner; 7.7%/2.1% for decks built purely
  // from utilize cards; 0.0% for decks with neither utilize nor armor. Only 30
  // live cards both utilize and carry armor, nearly all armor 1-3.

  // v4.19. Printed as "Unstunnable". A per-CARD protection like untargetable,
  // not a player-wide prevent_* like prevent_mill: it describes the permanent,
  // and stun picks a permanent. Filtered out of the pool the way untargetable
  // is, so an unstunnable body is never even selected and a stun aimed at a
  // board of them reports "nothing to stun" rather than silently fizzling.
  function isUnstunnable(entry, cardDefs) {
    return entryHasKeyword(entry, 'unstunnable', cardDefs);
  }

  // v4.22 Mute. The single predicate for "is this permanent blank right now".
  // Mute 0 blanks until it leaves play (mutePermanent); Mute N blanks for N of
  // the owner's turn-starts (muteCounters). Derived rather than stored so the
  // two writers can never disagree with the reader.
  function isMuted(entry) {
    return !!entry && (entry.mutePermanent === true || entry.muted === true);
  }

  // v4.22 Mute. THE chokepoint. Every board-entry read of a card's own
  // passives goes through here, so muting is implemented in ONE place instead
  // of at the 13 sites that previously reached into effects_json.passives
  // directly -- the shape that left v4.00/v4.01's filters inert in some
  // engines and not others. A muted entry answers with no passives at all.
  //
  // Card-DEF-keyed lookups (cardReshuffles, reshuffleSweep, hasCardPassive)
  // deliberately do NOT route through this: they ask about a card that is not
  // on the board -- in hand, deck or discard -- which mute cannot reach.
  function activePassives(entry, cardDefs) {
    if (!entry || isMuted(entry)) return null;
    return cardDefs[entry.card_id]?.effects_json?.passives;
  }

  // v4.22. Printed as "Unmutable". Read RAW via ignoreMute, never through the
  // chokepoint: routing it through activePassives would let a mute strip the
  // very keyword that forbade that mute. Unreachable in practice, because an
  // unmutable entry is filtered out of the pool before any mute can land, but
  // the circularity is not worth leaving in the code for someone to find.
  function isUnmutable(entry, cardDefs) {
    return entryHasKeyword(entry, 'unmutable', cardDefs, true);
  }


  // Same per-card passive lookup as isUntargetable/isIndestructible, but keyed
  // off a card DEF rather than a board entry -- anthemBonus works from a
  // card_id (the recipient may not be on the board yet) and so has no entry.
  function hasCardPassive(def, type) {
    const ps = def?.effects_json?.passives;
    return ps ? ps.some(p => p.type === type) : false;
  }

  function canFulfillUtilizeCost(ap, cardDefs, def, cost) {
    const target = (cost ?? def?.effects_json?.additional_cost)?.utilize;
    if (!target) return true;
    return ap.board.some(e => !e.utilized && matchesSupertype(cardDefs[e.card_id], target));
  }

  function fulfillUtilizeCost(ap, op, cardDefs, def, rand, log, plan, opPlan, cost) {
    const ac     = cost ?? def.effects_json.additional_cost;
    const cands  = ap.board.filter(e => !e.utilized && matchesSupertype(cardDefs[e.card_id], ac.utilize));
    const t      = selectUtilizeTarget(cands, plan?.cost_preference ?? 'random', rand, cardDefs);
    t.utilized   = true;
    if (log) log.push(`  utilized ${cardDefs[t.card_id]?.name || t.card_id} as additional cost`);
    firePassives(ap, 'on_utilized', ap, op, rand, cardDefs, log, false, plan, opPlan, t);
    firePassives(ap, 'on_self_utilized', ap, op, rand, cardDefs, log, false, plan, opPlan, t);
    firePassives(op, 'on_opponent_utilized', op, ap, rand, cardDefs, log, false, opPlan, plan, t);
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
  }

  // ── Sacrifice helpers (additional cost) ──────────────────────────────────────
  // Unlike utilize (taps a permanent, which stays on board), sacrifice destroys
  // it outright -- fires on_death + on_leave, same as kill_target, and interacts
  // with championLeave the same way any other permanent removal does.
  // sacrifice_exclude_tags (an array of 1+ tag names, e.g. ["soldier",
  // "scarecrow"]) is a sibling field on additional_cost, not part of the
  // `sacrifice` string itself -- keeps existing cards' additional_cost.sacrifice
  // value (a plain target_supertype string) untouched/unbroken. A permanent is
  // excluded if it carries ANY of the listed tags, not just "has any tag at all".
  function hasExcludedTag(cardDefs, cardId, excludeTags) {
    if (!excludeTags?.length) return false;
    const tags = cardDefs[cardId]?.tags ?? [];
    return excludeTags.some(t => tags.includes(t));
  }

  // only_tags is the positive counterpart to exclude_tags: when non-empty a
  // permanent is only sacrificeable if it carries at least one listed tag. An
  // empty/absent list means "no restriction", NOT "nothing matches", so
  // existing cards are unaffected. exclude_tags wins when both are set --
  // "never sacrifice a soldier" reads as absolute. Effects only: deliberately
  // NOT wired into canFulfillSacrificeCost(), where it would make a card
  // conditionally unplayable. Mirrors validate.go's lacksRequiredTag().
  function lacksRequiredTag(cardDefs, cardId, onlyTags) {
    if (!onlyTags?.length) return false;
    const tags = cardDefs[cardId]?.tags ?? [];
    return !onlyTags.some(t => tags.includes(t));
  }

  function canFulfillSacrificeCost(ap, cardDefs, def, cost) {
    const ac = cost ?? def?.effects_json?.additional_cost;
    const target = ac?.sacrifice;
    if (!target) return true;
    return ap.board.some(e => matchesSupertype(cardDefs[e.card_id], target)
      && !entryExcluded(e, cardDefs, ac.sacrifice_exclude_tags));
  }

  function fulfillSacrificeCost(ap, op, cardDefs, def, rand, log, plan, opPlan, cost) {
    const ac    = cost ?? def.effects_json.additional_cost;
    const cands = ap.board.filter(e => matchesSupertype(cardDefs[e.card_id], ac.sacrifice)
      && !entryExcluded(e, cardDefs, ac.sacrifice_exclude_tags));
    const t     = selectTarget(cands, plan?.cost_preference ?? 'random', rand, cardDefs);
    if (log) log.push(`  sacrificed ${cardDefs[t.card_id]?.name || t.card_id} as additional cost`);
    fireTriggerOn(t, 'on_death', ap, op, rand, cardDefs, log, plan, opPlan);
    fireTriggerOn(t, 'on_leave', ap, op, rand, cardDefs, log, plan, opPlan);
    ap.board = ap.board.filter(c => c !== t);
    if (!championLeave(ap, t.card_id, log, cardDefs)) toDiscardOrExile(ap, t, cardDefs, rand, log, op, plan, opPlan);
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
  }

  // Pay Honor: a flat Honor cost, not tied to any board state. Unlike utilize/
  // sacrifice, prevent_honor_loss does NOT block this -- that passive protects
  // against Honor being taken from you, not against Honor you choose to spend.
  function canFulfillHonorCost(ap, def, cost) {
    const amount = (cost ?? def?.effects_json?.additional_cost)?.honor;
    if (!amount) return true;
    return ap.honor >= amount;
  }

  function fulfillHonorCost(ap, op, cardDefs, def, rand, log, plan, opPlan, cost) {
    const ac = cost ?? def.effects_json.additional_cost;
    ap.honor -= ac.honor; tally(ap, 'honor_lost', ac.honor);
    if (log) log.push(`  paid ${ac.honor} honor as additional cost → ${ap.honor} honor`);
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
  }

  // Pay Life / Pay Shields: flat costs mirroring Pay Honor exactly. Gated by
  // >= beforehand, so life/shields can never go negative via a cost.
  function canFulfillLifeCost(ap, def, cost) {
    const amount = (cost ?? def?.effects_json?.additional_cost)?.life;
    if (!amount) return true;
    return ap.life >= amount;
  }

  function fulfillLifeCost(ap, op, cardDefs, def, rand, log, plan, opPlan, cost) {
    const ac = cost ?? def.effects_json.additional_cost;
    ap.life -= ac.life; tally(ap, 'life_lost', ac.life);
    if (ac.life > 0) ap.lifeLossCause = 'self';
    if (log) log.push(`  paid ${ac.life} life as additional cost → ${ap.life} life`);
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
  }

  function canFulfillShieldsCost(ap, def, cost) {
    const amount = (cost ?? def?.effects_json?.additional_cost)?.shields;
    if (!amount) return true;
    return ap.shields >= amount;
  }

  function fulfillShieldsCost(ap, op, cardDefs, def, rand, log, plan, opPlan, cost) {
    const ac = cost ?? def.effects_json.additional_cost;
    ap.shields -= ac.shields;
    if (log) log.push(`  paid ${ac.shields} shields as additional cost → ${ap.shields} shields`);
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
  }

  // Discard N cards from your own hand as a cost. Unlike utilize/sacrifice/honor,
  // this touches the SAME hand array the card being played still sits in, so the
  // caller must exclude that card's own index and use the returned (possibly
  // shifted) index afterward instead of its original one.
  // inHand: whether the card being played is itself sitting in hand. True for
  // every ordinary play, FALSE for a champion cast -- a champion comes from the
  // champion zone, so it occupies no hand slot and `>` would demand one card
  // more than the cost actually needs.
  function canFulfillDiscardCost(ap, def, inHand = true, cost) {
    const n = (cost ?? def?.effects_json?.additional_cost)?.discard;
    if (!n) return true;
    return inHand ? ap.hand.length > n : ap.hand.length >= n;
  }

  function fulfillDiscardCost(ap, op, cardDefs, def, excludeIdx, rand, log, plan, opPlan, cost) {
    const ac   = cost ?? def.effects_json.additional_cost;
    let n = ac.discard;
    let exclude = excludeIdx;
    while (n > 0) {
      const candidates = [];
      for (let i = 0; i < ap.hand.length; i++) if (i !== exclude) candidates.push(i);
      if (!candidates.length) break;
      const idx = pickDiscardFrom(candidates, ap.hand, cardDefs, plan, rand);
      if (idx < 0) break;
      if (log) log.push(`  discarded ${cardDefs[ap.hand[idx]]?.name || ap.hand[idx]} as additional cost`);
      arriveInDiscard(ap, ap.hand.splice(idx, 1)[0], cardDefs, rand, log, 'cost', op, plan, opPlan);
      if (idx < exclude) exclude--;
      n--;
    }
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
    return exclude;
  }

  // Recycle N cards from your own discard pile as a cost -- shuffles them
  // back into your library. Unlike discard cost, this touches a completely
  // separate zone from the hand (no index-shifting concern), so the check
  // is a plain >= rather than discard's > (which reserves the played card's
  // own hand slot).
  function canFulfillRecycleCost(ap, def, cost) {
    const n = (cost ?? def?.effects_json?.additional_cost)?.recycle;
    if (!n) return true;
    return ap.discard.length >= n;
  }

  function fulfillRecycleCost(ap, op, cardDefs, def, rand, log, plan, opPlan, cost) {
    const ac    = cost ?? def.effects_json.additional_cost;
    const n     = Math.min(ac.recycle, ap.discard.length);
    const cpref = plan?.cost_preference ?? 'random';
    let pool = ap.discard.slice();
    const picked = [];
    for (let i = 0; i < n; i++) {
      const cid = selectPileCard(pool, cpref, cardDefs, rand);
      pool.splice(pool.indexOf(cid), 1);
      picked.push(cid);
    }
    ap.discard = pool;
    ap.deck = shuffle([...ap.deck, ...picked], rand);
    tally(ap, 'cards_recycled', n);
    if (log) log.push(`  recycled ${n} card${n !== 1 ? 's' : ''} from discard into library as additional cost`);
    if (n > 0) fireRecycle(ap, op, rand, cardDefs, log, false, plan, opPlan);
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
  }

  // Exile N cards from your own discard pile as a cost -- same shape as
  // the recycle cost above, minus the "shuffle picked cards into deck"
  // step: they're gone, not shuffled anywhere.
  function canFulfillExileDiscardCost(ap, def, cost) {
    const n = (cost ?? def?.effects_json?.additional_cost)?.exile_discard;
    if (!n) return true;
    return ap.discard.length >= n;
  }

  function fulfillExileDiscardCost(ap, op, cardDefs, def, rand, log, plan, opPlan, cost) {
    const ac    = cost ?? def.effects_json.additional_cost;
    const n     = Math.min(ac.exile_discard, ap.discard.length);
    const cpref = plan?.cost_preference ?? 'random';
    let pool = ap.discard.slice();
    for (let i = 0; i < n; i++) {
      const cid = selectPileCard(pool, cpref, cardDefs, rand);
      pool.splice(pool.indexOf(cid), 1);
    }
    ap.discard = pool;
    tally(ap, 'cards_exiled', n);
    if (log) log.push(`  exiled ${n} card${n !== 1 ? 's' : ''} from discard as additional cost`);
    if (ac.effect) applyEffect(ac.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
  }

  // ── v4.48 Supplement ──────────────────────────────────────────────────────
  // An OPTIONAL additional cost with a bonus. Same vocabulary as
  // additional_cost (which is mandatory), plus `energy`, which additional_cost
  // has no term for because a card's own cost covers it.
  //
  // THE THREE RULES THAT MAKE "OPTIONAL" DETERMINISTIC in an autobattler,
  // where nobody is there to choose:
  //   * ALL OR NOTHING -- every component is tested before any is paid. A
  //     half-paid supplement would be invisible to a parity run, since all
  //     four engines would half-pay identically.
  //   * it NEVER changes playability -- evaluated only once the card is
  //     already being played, after its base cost, its To Play gate and any
  //     mandatory additional_cost.
  //   * the bonus fires ONCE, after every component is paid. Deliberately
  //     unlike additional_cost, which applies its rider inside each fulfill*
  //     helper and so fires it per component on a multi-component cost.
  //
  // Battle plan: `supplements: 'off'` disables them for the deck, and
  // `supplement_reserve` is energy that must REMAIN afterwards -- without it a
  // draw-4 happily eats the six energy the deck needed for its bomb. Kept
  // separate from energy_hold, which decides whether a CARD is played at all;
  // this decides whether an optional extra is paid on a card already playing.
  function supplementOf(def) {
    const s = def?.effects_json?.supplement;
    return (s && typeof s === 'object') ? s : null;
  }

  function canPaySupplement(ap, def, cardDefs, plan) {
    const s = supplementOf(def);
    if (!s) return false;
    if ((plan?.supplements ?? 'on') === 'off') return false;
    const reserve = Math.max(0, Math.floor(plan?.supplement_reserve ?? 0) || 0);
    const need    = Math.max(0, Math.floor(s.energy ?? 0) || 0);
    // v4.61: the same pools canAfford reads, INCLUDING restricted energy that
    // matches this card -- paySupplement's spendEnergy spends that first, so
    // leaving it out here skipped supplements the card could legally pay for.
    // The reserve is ORDINARY (basic + ephemeral) energy that must survive:
    // only the part restricted energy does not cover comes out of it.
    if (need > 0) {
      const matching = p_restrictedMatching(ap, def);
      const ordinary = ap.energy + ephemeralFor(ap, def);   // v4.64 Patient
      if (matching + ordinary < need) return false;
      if (ordinary - Math.max(0, need - matching) < reserve) return false;
    }
    // The SHARED checks, with the supplement standing in for additional_cost.
    // A second copy of this validation is exactly what drifts silently --
    // Go's fulfillUtilize fires three passives where a hand-rolled version
    // fired one, and nothing would have reported it until a parity break.
    const pay = Object.assign({}, s); delete pay.effect;
    if (!canFulfillUtilizeCost(ap, cardDefs, def, pay))   return false;
    if (!canFulfillSacrificeCost(ap, cardDefs, def, pay)) return false;
    if (!canFulfillHonorCost(ap, def, pay))               return false;
    if (!canFulfillDiscardCost(ap, def, true, pay))       return false;
    if (!canFulfillShieldsCost(ap, def, pay))             return false;
    if (!canFulfillRecycleCost(ap, def, pay))             return false;
    if (!canFulfillExileDiscardCost(ap, def, pay))        return false;
    // Life is checked here rather than through canFulfillLifeCost: paying a
    // MANDATORY cost may take you to exactly 0, but an OPTIONAL extra must
    // never be the thing that kills you.
    if (pay.life && ap.life <= pay.life) return false;
    return true;
  }

  // v4.54: does this effect (or anything nested in it) act on "this card" --
  // the board entry that fired it? Such an effect cannot resolve while the card
  // is still in hand, which is where a supplement is paid, so for a PERMANENT
  // it is held back and applied once the card is on the board (see
  // paySupplement). Kept to an explicit list, identical in all four engines:
  // everything else keeps its v4.48 timing, which is what leaves every
  // existing supplement card replaying bit-identically.
  function effectNeedsSource(eff) {
    if (!eff || typeof eff !== 'object') return false;
    if (eff.type === 'add_counter') return true;
    if (eff.self || eff.exclude_self || eff.sacrifice_self) return true;
    for (const sub of (eff.then || [])) if (effectNeedsSource(sub)) return true;
    for (const sub of (eff['else'] || [])) if (effectNeedsSource(sub)) return true;
    return false;
  }

  // Payment order matches additional_cost's own, so the two read alike and
  // consume RNG in the same sequence.
  //
  // Returns the supplement's effect when it must wait for the card's board
  // entry (v4.54: a permanent whose effect needs "this card", e.g. "put 57
  // study counters on this card"), else null after applying it here as
  // before. The caller applies a returned effect with the new entry as its
  // source, right after the entry is placed and before its on_enter.
  function paySupplement(ap, op, cardDefs, def, rand, log, plan, opPlan) {
    const s = supplementOf(def);
    if (!s) return null;
    // The rider is STRIPPED before the shared fulfillers run: additional_cost
    // applies its effect inside each helper, which would fire the bonus once
    // per component. A supplement's bonus fires once, at the end.
    const pay  = Object.assign({}, s); delete pay.effect;
    const need = Math.max(0, Math.floor(s.energy ?? 0) || 0);
    // Its payments are collected and printed UNDER a line that names the
    // card, and say "for the supplement": logged bare, "paid 6 life as
    // additional cost" read as a mandatory cost and "supplement paid" named
    // no card, so a player could not tell which play paid it (Fry Guy's
    // three Time Machines, 2026-10-28). Wording only -- nothing is reordered
    // in the game itself, and no RNG is touched.
    const sub  = log ? [] : null;
    if (need > 0) spendEnergy(ap, need, def);
    if (pay.utilize)       fulfillUtilizeCost(ap, op, cardDefs, def, rand, sub, plan, opPlan, pay);
    if (pay.sacrifice)     fulfillSacrificeCost(ap, op, cardDefs, def, rand, sub, plan, opPlan, pay);
    if (pay.honor)         fulfillHonorCost(ap, op, cardDefs, def, rand, sub, plan, opPlan, pay);
    if (pay.discard)       fulfillDiscardCost(ap, op, cardDefs, def, -1, rand, sub, plan, opPlan, pay);
    if (pay.life)          fulfillLifeCost(ap, op, cardDefs, def, rand, sub, plan, opPlan, pay);
    if (pay.shields)       fulfillShieldsCost(ap, op, cardDefs, def, rand, sub, plan, opPlan, pay);
    if (pay.recycle)       fulfillRecycleCost(ap, op, cardDefs, def, rand, sub, plan, opPlan, pay);
    if (pay.exile_discard) fulfillExileDiscardCost(ap, op, cardDefs, def, rand, sub, plan, opPlan, pay);
    if (log) {
      log.push(`  supplement paid for ${def.name || def.card_id}${need > 0 ? ` (${need} energy)` : ''}`);
      for (const l of sub) log.push(l.replace(' as additional cost', ' for the supplement'));
    }
    if (s.effect && isPermanent(def) && effectNeedsSource(s.effect)) return s.effect;
    if (s.effect) applyEffect(s.effect, ap, op, rand, cardDefs, log, plan, undefined, undefined, opPlan);
    return null;
  }

  // ── Win condition ─────────────────────────────────────────────────────────────
  // Merges each player's accumulated achievement stats (see the per-turn
  // damage/poison snapshot-delta and the honor/mill counters set at their
  // mutation sites, both in _simSingleGame) onto a game-result object right
  // before it's returned. checkWin()/​_simSingleGame() return from many
  // different points mid-loop, so this is called at every return site rather
  // than computed once at a single end-of-function exit.
  function withStats(result, players) {
    const [p0, p1] = players;
    result.dmg_a    = p0.maxTurnDamage || 0;         result.dmg_b    = p1.maxTurnDamage || 0;
    result.poison_a = p0.maxTurnPoison || 0;         result.poison_b = p1.maxTurnPoison || 0;
    result.mill_a   = p0.milledOpponentTotal || 0;   result.mill_b   = p1.milledOpponentTotal || 0;
    result.honor_a  = Math.max(p0.honorGainedTotal || 0, p0.opponentHonorLostTotal || 0);
    result.honor_b  = Math.max(p1.honorGainedTotal || 0, p1.opponentHonorLostTotal || 0);
    // For a life-loss result, say HOW the life ran out -- 'health' alone merges
    // combat, spell damage, poison and self-inflicted costs into one bucket,
    // which is exactly the distinction the deck-testing breakdown needs.
    // Extra field, never submitted: see simulateMatch's `games` note.
    if (result.reason === 'health' && result.winner !== -1) {
      const loser = result.winner === 0 ? p1 : p0;
      result.cause = loser.lifeLossCause || 'damage';
    }
    return result;
  }

  // Refreshes ap's running single-turn damage/poison peaks against op's
  // CURRENT life/poison vs. the values snapshotted at the start of ap's turn.
  // Must be called right before every mid-turn checkWin() (and again at
  // turn-end) -- checkWin's early return bakes in maxTurnDamage/maxTurnPoison
  // via withStats() immediately, so if this only ran once at the bottom of
  // the turn loop, a lethal blow (checkWin returning truthy from inside step
  // 5/6c, before that bottom code ever runs) would win the game without ever
  // crediting its own damage -- silently excluding exactly the biggest, most
  // achievement-relevant hits. Safe to call repeatedly (idempotent max()).
  function updateTurnStats(ap, op, turnLifeStart, turnPoisonStart) {
    ap.maxTurnDamage = Math.max(ap.maxTurnDamage || 0, Math.max(0, turnLifeStart - op.life));
    ap.maxTurnPoison = Math.max(ap.maxTurnPoison || 0, Math.max(0, op.poison - turnPoisonStart));
  }

  function checkWin(players, turn, honorThreshold, cardDefs, log) {
    const ht = honorThreshold ?? HONOR_TO_WIN;
    const [p0, p1] = players;
    // Deck-out and life-loss are merged into one elimination check per
    // player, not two separately-prioritized tiers, because a single combat
    // action can trigger both conditions on DIFFERENT players at once --
    // e.g. Void Siphon's drawlink draws cards equal to combat damage dealt,
    // so the attacker can deck out in the very same hit that drops the
    // defender to <=0 life. Checking deck-out in total isolation (as this
    // used to) returned as soon as it saw the attacker's deck-out and never
    // even looked at the defender's life, handing the attacker a loss
    // instead of the tie this should be. Any future combat/melee trigger
    // with a similar "acting player risks their own loss condition" shape
    // (not just drawlink) is covered by this same merge.
    // (reported: Fry Guy/phodos, 2026-08-05)
    const dk0 = !!p0.deckedOut, dk1 = !!p1.deckedOut;
    const elim0 = dk0 || p0.life <= 0, elim1 = dk1 || p1.life <= 0;
    // A single player meeting a WIN and a LOSS condition at the same instant
    // is a draw, checked before everything else. An honor deck crosses the
    // honor threshold and its own leftover draw triggers then empty its
    // library in the same resolution batch; elimination used to be checked
    // first, so the opponent was handed a deck_out win at 200+ honor.
    // (reported: Fry Guy, 2026-08-30 -- mirrors validate.go's checkWinCore)
    // v4.46. A card's win condition counts as a WIN everywhere honor does, so
    // it inherits the win_and_loss draw rule below rather than getting its own.
    const ce0 = winConditionMet(p0, p1, cardDefs), ce1 = winConditionMet(p1, p0, cardDefs);
    const cw0 = !!ce0, cw1 = !!ce1;
    if (((p0.honor >= ht || cw0) && elim0) || ((p1.honor >= ht || cw1) && elim1))
      return { winner: -1, reason: 'win_and_loss', turns: turn };
    if (elim0 && elim1) return { winner: -1, reason: 'mutual_death', turns: turn };
    if (elim1)          return { winner:  0, reason: dk1 ? 'deck_out' : 'health', turns: turn };
    if (elim0)          return { winner:  1, reason: dk0 ? 'deck_out' : 'health', turns: turn };
    // Dishonor (honor <= -ht) is the symmetric loss counterpart to the honor
    // win condition, at the same threshold (so chaos's honor_threshold_50
    // halves both). A single player can never satisfy both hw/hl at once
    // (contradictory), so checking the two mutual cases first and the four
    // individual flags after is exhaustive and non-conflicting -- same
    // pattern as the deck_out/health checks above, just twice the flags.
    const hw0 = p0.honor >= ht, hw1 = p1.honor >= ht;
    const hl0 = p0.honor <= -ht, hl1 = p1.honor <= -ht;
    // v4.46. Unlike hw/hl, a card win and the owner's OWN dishonor are not
    // contradictory -- a 250-life board can sit at -200 honor -- so this pair
    // needs the win_and_loss draw the honor arm never had to state.
    if ((cw0 && hl0) || (cw1 && hl1))
      return { winner: -1, reason: 'win_and_loss', turns: turn };
    if (hw0 && hw1) return { winner: -1, reason: 'mutual_honor',    turns: turn };
    if (hl0 && hl1) return { winner: -1, reason: 'mutual_dishonor', turns: turn };
    // Both players winning at once, in any mix of honor and card (the pure
    // honor/honor case already returned above as mutual_honor).
    if ((hw0 || cw0) && (hw1 || cw1))
      return { winner: -1, reason: 'mutual_win', turns: turn };
    if (hw0)        return { winner:  0, reason: 'honor',           turns: turn };
    if (hw1)        return { winner:  1, reason: 'honor',           turns: turn };
    if (cw0 || cw1) {
      const w = cw0 ? 0 : 1;
      // Logs are JS-only, so naming the card costs nothing in parity. checkWin
      // returns the instant it fires and the sim stops, so this pushes once.
      if (log) {
        // §src: is a STANDALONE marker line, not a prefix -- the visualizer
        // matches /^§src:(\d+)/ and attributes the NEXT line to that card.
        log.push(`§src:${(cw0 ? ce0 : ce1).card_id}`);
        log.push(`  ${cw0 ? 'player1' : 'player2'} meets a win condition and wins the game`);
      }
      return { winner: w, reason: 'card_win', turns: turn };
    }
    if (hl0)        return { winner:  1, reason: 'dishonor',        turns: turn };
    if (hl1)        return { winner:  0, reason: 'dishonor',        turns: turn };
    return null;
  }

  // ── Effect dispatcher ─────────────────────────────────────────────────────────
  // ap = card owner (gains benefits), op = opponent (receives negative effects)
  // fromPassive stops passive-triggered effects from firing further passives,
  // breaking the mutual applyEffect <-> firePassives recursion: without it, a
  // card with e.g. an on_damage_dealt passive whose own effect deals damage
  // recurses forever (each deal_damage_to_player fires on_damage_dealt again)
  // until the call stack overflows. Mirrors shell/validate.go's fromPassive.
  //
  // fromPassive alone doesn't cover every chain, though: two DIFFERENT cards'
  // on_leave triggers can bounce off each other forever too -- e.g. a creature
  // whose on_leave returns an opponent creature to hand, where that returned
  // creature has the same on_leave effect, ping-pongs between both boards for
  // as long as each side still has a qualifying creature (confirmed in a real
  // cohort match: 7 copies of such a card per deck recursed 60+ levels deep
  // before this guard was added). MAX_EFFECT_DEPTH is a blunt but general
  // backstop for any such chain, passive- or trigger-driven, known or not:
  // past this depth, further chained effects are silently dropped rather than
  // crashing the whole simulation.
  const MAX_EFFECT_DEPTH = 40;
  // Backstop for step 5 (play cards greedily until nothing more is
  // affordable/playable) -- that loop has no natural iteration cap of its
  // own, unlike the turn loop (MAX_TURNS) or effect recursion
  // (MAX_EFFECT_DEPTH). A deck combining a net-energy-positive card (e.g.
  // costs 3, grants 5 back) with cards that shuffle discard back into the
  // library (Salvage Ritual/Dedicated Recall/Temporal Rift/Honor Blitz)
  // creates a genuinely infinite turn: cards cycle deck->hand->discard->
  // deck forever while energy never runs out, so the while-loop below never
  // exits on its own (confirmed live: a real player's deck hung the tab
  // solid, 2026-08-03). 300 is far above any legitimate single-turn play
  // count (deck size is 100) but still finite -- past it, the rest of the
  // turn's plays are silently skipped rather than hanging.
  const MAX_PLAYS_PER_TURN = 300;
  let __effectDepth = 0;

  // ── Structured events (2026-10-13) ───────────────────────────────────────
  // The viewers reconstruct what happened by matching ~108 regexes against the
  // prose log, and every attribution bug this month came from that. The facts
  // are all in scope at the moment a line is written -- the source entry, the
  // effect type, the resolved amount, the owner -- so record them there
  // instead of deriving them back out of a sentence.
  //
  // Module scope rather than a threaded parameter because `log` already
  // reaches every one of the 260 push sites and `sub` buffers would make an
  // index meaningless: fireTriggerOn collects a trigger's lines into a
  // temporary array and flushes it into the real log afterwards, so a position
  // captured at push time is not the position the line ends up at. Events
  // instead carry the SNAPSHOT they belong to, which is what a viewer actually
  // needs, and their relative order already matches the log's.
  //
  // Only recordBattle() turns this on. runCohort()/_simSingleGame() -- the
  // validation path -- leave it null, so this is one null check per push and
  // allocates nothing.
  let __events = null;
  let __snap   = 0;

  function emitEvent(o) {
    if (!__events) return;
    o.snap = __snap;
    __events.push(o);
  }

  // The owner of an effect, as a player index, or null when unknown.
  function piOf(p) {
    return (p && (p.pIndex === 0 || p.pIndex === 1)) ? p.pIndex : null;
  }
  // Shared iid counter, reset to 0 at the start of each game (see
  // _simSingleGame/recordBattle below) -- declared at this outer scope
  // (like __effectDepth above) specifically so Reanimate effects (fired
  // from inside applyEffect/firePassives, not from playOneCard/
  // tryPlayChampion) can also mint a fresh, globally-unique iid for a
  // newly-created board entry without needing iid threaded through the
  // entire applyEffect call chain. playOneCard/tryPlayChampion keep
  // receiving `iid` as an explicit parameter exactly as before; both call
  // sites just alias it to this same shared object now instead of a
  // fresh per-game one, so existing behavior (fresh ids per game) is
  // unchanged.
  let __iid = { val: 0 };
  // source (optional): the board entry whose trigger/passive is firing this
  // effect, if any -- threaded through so effects like sacrifice_own_permanent
  // can offer an exclude_self option (matched by iid, since amount/card_id
  // alone can't distinguish which copy of a duplicated card is "itself").
  //
  // opPlan (optional, appended last): the OPPONENT's battle plan -- i.e.
  // whichever player is NOT ap. Needed by a handful of effects/watchers/
  // passives inside applyEffectImpl that act on op's board (kill_target,
  // destroy_target, the on_opponent_X passives, on_tag_leave/on_tag_death
  // watchers on a removed opponent permanent, etc.) -- plan alone only ever
  // covers ap's own board.
  // A §src marker names the source of the lines that FOLLOW it, so it has to
  // be re-asserted whenever the source changes -- including when it changes
  // BACK after a nested trigger wrote its own. A mass destroy emits one line
  // per kill and every kill fires the dying card's on_leave, so without this
  // the rest of the wipe was logged under the last dead card's marker (up to
  // 11% of marked lines on a destruction deck; reported 2026-10-13).
  // `log.__src` is the id the most recent marker in THIS buffer names;
  // fireTriggerOn carries it across its sub-buffer by hand, since that buffer
  // is flushed into the outer log only after the effect has finished.
  function markSrc(log, entry) {
    if (!log || !entry || entry.card_id == null) return;
    if (log.__src === entry.card_id) return;
    log.push(`§src:${entry.card_id}`);
    log.__src = entry.card_id;
  }

  function applyEffect(eff, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan) {
    if (__effectDepth >= MAX_EFFECT_DEPTH) {
      if (log) log.push(`  (effect chain too deep — further chained effects stopped)`);
      return;
    }
    __effectDepth++;
    try {
      return applyEffectImpl(eff, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
    } finally {
      __effectDepth--;
    }
  }
  // Counts cards tagged `tag` in the given zone ('discard' | 'hand' |
  // default 'board') of player p. Shared by count_source and
  // cost_scaling's 'tag_count' terms, which resolve identically.
  function countTagInZone(p, tag, zone, cardDefs) {
    const hasTag = cid => cardDefs[cid]?.tags?.includes(tag);
    if (zone === 'discard') return p.discard.filter(hasTag).length;
    if (zone === 'hand')    return p.hand.filter(hasTag).length;
    return p.board.filter(e => entryHasTag(e, tag, cardDefs)).length;
  }

  // typeburst_source's fixed 5-value set -- matches admin/cards.php's
  // supertype dropdown.
  const TYPEBURST_SUPERTYPES = ['creature', 'relic', 'structure', 'enchantment', 'spell'];

  // Returns how many of the 5 canonical supertypes have at least one
  // representative across p's own board + discard combined (always self --
  // no side axis, per design). Capped at 5 by construction.
  function typeburstCount(p, cardDefs) {
    const seen = new Set();
    const mark = cid => {
      const def = cardDefs[cid];
      if (!def) return;
      for (const st of TYPEBURST_SUPERTYPES) {
        if (!seen.has(st) && matchesSupertype(def, st)) seen.add(st);
      }
    };
    for (const e of p.board) mark(e.card_id);
    for (const cid of p.discard) mark(cid);
    return seen.size;
  }

  // Reads one of a fixed set of scalar player stats by name -- used by
  // cost_scaling's 'stat' term kind. Unrecognized names resolve to 0.
  function playerStat(p, stat) {
    switch (stat) {
      case 'honor':   return p.honor;
      case 'shields': return p.shields;
      case 'life':    return p.life;
      case 'poison':  return p.poison;
      case 'energy':  return p.energy;
      default:        return 0;
    }
  }

  // ── v4.41 To Play gates ────────────────────────────────────────────────────
  // A "To Play:" condition on a card: a PURE PREDICATE with no payment, which
  // is what separates it from the nine additional_cost gates beside it in
  // playOneCard. There is no fulfill* twin because nothing is ever spent.
  //
  // Shape -- `effects_json.play_requires`, a LIST, every entry of which must
  // hold (AND):
  //   { kind: 'stat',      stat: 'life',   side: 'self',     op: 'gte', amount: 20 }
  //   { kind: 'tag_count', tag: 'soldier', zone: 'board',    op: 'gte', amount: 3 }
  //   { kind: 'zone_size', zone: 'discard', side: 'opponent', op: 'gte', amount: 20 }
  // side defaults to 'self', zone to 'board', op to 'gte'.
  //
  // A LIST of independently-compared conditions rather than cost_scaling's
  // "sum the terms, compare once" shape, deliberately: a gate is a boolean,
  // and summing unrelated metrics ("your soldiers plus their discard size")
  // is almost never what a card means, whereas two printed "To Play:" clauses
  // read as AND to any player.
  //
  // `zone_size` is its own kind rather than a tag_count with an empty tag,
  // because an empty tag is a KILL SWITCH elsewhere in this engine (it matches
  // nothing, so the gate would read "0 >= N" and lock the card out forever
  // instead of counting the zone).
  //
  // APPLIED TO CHAMPION CASTS TOO (owner's call, 2026-10-18: "champions should
  // be gated as any card"). Deliberately UNLIKE the nine additional_cost gates
  // and cost_scaling, which tryPlayChampion still bypasses: skipping a COST
  // makes a champion cheaper, while skipping a CONDITION would make a gated
  // card strictly better as a champion than in the deck, inverting its own
  // text. See ENGINE_PLAY_GATES_AND_ENERGY.md.
  const PLAY_GATE_ZONES = ['board', 'hand', 'discard', 'deck'];

  function zoneSize(p, zone) {
    switch (zone) {
      case 'hand':    return p.hand.length;
      case 'discard': return p.discard.length;
      case 'deck':    return p.deck.length;
      default:        return p.board.filter(e => !e.destroyed).length;
    }
  }

  function playGateValue(cond, ap, op, cardDefs) {
    const p = cond.side === 'opponent' ? op : ap;
    if (!p) return 0;
    switch (cond.kind) {
      case 'stat':      return playerStat(p, cond.stat);
      case 'zone_size': return zoneSize(p, cond.zone || 'board');
      // v4.42. A gross per-turn total -- "you must have gained 3 Life this
      // turn". Reads the tally, so it is true of what HAPPENED this turn
      // rather than of the state left behind.
      case 'turn_tally': return p.turnTally[cond.metric] || 0;
      // v4.72. One specific card, by id -- tokens and manifested copies carry
      // the id they were made from, so "if you control no Queen" counts them.
      case 'card_count': return countCardInZone(p, cond.card_id, cond.zone || 'board');
      default:          return countTagInZone(p, cond.tag, cond.zone || 'board', cardDefs);
    }
  }

  // v4.72. Copies of ONE card (by id) in a zone. The board count skips
  // destroyed entries, like zoneSize; must match validate.go/engine.go's
  // countCardInZone and validate.py's _count_card_in_zone.
  function countCardInZone(p, cardId, zone) {
    const id = +cardId || 0;
    if (!id) return 0;
    switch (zone) {
      case 'hand':    return p.hand.filter(c => c === id).length;
      case 'discard': return p.discard.filter(c => c === id).length;
      case 'deck':    return p.deck.filter(c => c === id).length;
      default:        return p.board.filter(e => !e.destroyed && e.card_id === id).length;
    }
  }

  function playGateCompare(have, op_, want) {
    switch (op_) {
      case 'lte': return have <= want;
      case 'gt':  return have >  want;
      case 'lt':  return have <  want;
      case 'eq':  return have === want;
      default:    return have >= want;          // 'gte'
    }
  }

  // ── v4.46 win conditions ──────────────────────────────────────────────────
  // "You win the game" as a board-state condition, reusing the v4.41 play-gate
  // vocabulary verbatim (stat / tag_count / zone_size / turn_tally), so there
  // is no second condition language to hold in parity across four engines.
  //
  // Carried as a PASSIVE -- { type: 'win_condition', requires: [ ...gates ] } --
  // and NOT as a sibling of effects_json.play_requires, for one reason: a
  // passive is read through activePassives(), the v4.22 mute chokepoint, so a
  // muted card stops winning the game for free. Hanging it off effects_json
  // directly would have needed its own mute test written four times, which is
  // exactly the shape of the v4.23 bug (hasPassive was mute-aware in JS only,
  // so a muted prevent_* still protected its owner in the validators).
  //
  // `side` inside each gate is relative to the CARD'S OWNER, so side:'opponent'
  // reads the other player ("you win when they have no cards in hand").
  //
  // Fails CLOSED: a win_condition with no requires list wins nothing. An empty
  // list is vacuously true under an AND-walk, which would hand the game to
  // whoever drew a malformed card on the turn they played it.
  function winConditionMet(p, other, cardDefs) {
    if (!p || !p.board) return null;
    for (const e of p.board) {
      if (e.destroyed) continue;
      const ps = activePassives(e, cardDefs);        // v4.22 mute chokepoint
      if (!ps) continue;
      for (const pass of ps) {
        if (pass.type !== 'win_condition') continue;
        // `win_requires`, NOT `requires`: passiveDef.requires is already taken --
        // a single {tag,zone,side,min} object read by requiresHolds. Untyped JS
        // would happily hold a list there; both Go engines would fail to
        // unmarshal an array into *passiveRequires and the card would die at
        // payload decode.
        const reqs = pass.win_requires;
        if (!Array.isArray(reqs) || !reqs.length) continue;   // fails closed
        let all = true;
        for (const c of reqs) {
          const have = playGateValue(c, p, other, cardDefs);
          // `|| 0`, matching playGateBlocker above and both Go engines
          // (c.Amount is 0 when absent) and validate.py (`or 0`). An `?? 1`
          // default here would make an amount-less gate mean something
          // different in JS than in the other three.
          if (!playGateCompare(have, c.op, c.amount || 0)) { all = false; break; }
        }
        if (all) return e;                           // the entry, for the log
      }
    }
    return null;
  }

  // The first unmet condition, phrased for the replay feed, or null when the
  // card may be played. This is the ONE evaluator in this engine: playGatesHold
  // below is derived from it rather than re-walking the list, because two
  // walkers that disagree would let a card through with its gate unmet.
  // The other three engines implement this same walk as a plain boolean
  // (`playGatesHold`/`play_gates_hold`), since they have no log sites at all;
  // there is deliberately no boolean twin here, because a second walker in
  // this engine could disagree with this one and let a gated card through.
  function playGateBlocker(def, ap, op, cardDefs) {
    return gateListBlocker(def?.effects_json?.play_requires, ap, op, cardDefs);
  }

  // The walk itself, shared by playGateBlocker (a card's "To Play:") and v4.72's
  // per-ability `only_if` (applyEffectImpl), so the two can never disagree on
  // what a gate means.
  function gateListBlocker(gates, ap, op, cardDefs) {
    if (!Array.isArray(gates) || !gates.length) return null;
    for (const c of gates) {
      const have = playGateValue(c, ap, op, cardDefs);
      const want = c.amount || 0;
      if (playGateCompare(have, c.op, want)) continue;
      const whose = c.side === 'opponent' ? "opponent's " : 'your ';
      // Every arm names the SIDE. Without it an opponent-side stat gate read
      // as the player's own -- "needs at most 90 life (has 100)" on a card
      // whose condition is about the OPPONENT's life. And 'gte' gets "at
      // least" like every other comparator rather than a bare number, so the
      // five ops read uniformly.
      const n = `${{ lte: 'at most', gt: 'more than', lt: 'fewer than',
                     eq: 'exactly' }[c.op] || 'at least'} ${want}`;
      // Capitalised to match the card's own sentence ("...at least 20 Life"),
      // so the replay and the card face name the stat the same way.
      if (c.kind === 'stat') {
        const st = String(c.stat || '');
        return `needs ${whose}${st.charAt(0).toUpperCase()}${st.slice(1)} ${n} (has ${have})`;
      }
      if (c.kind === 'turn_tally') {
        // The metric name already reads as the phrase it is ("life gained"),
        // so no per-metric wording is needed here.
        return `needs ${n} ${String(c.metric || '').replace(/_/g, ' ')} by ${
          c.side === 'opponent' ? 'your opponent' : 'you'} this turn (has ${have})`;
      }
      if (c.kind === 'zone_size') return `needs ${n} cards in ${whose}${c.zone || 'board'} (has ${have})`;
      if (c.kind === 'card_count') {
        return `needs ${n} ${cardDefs[c.card_id]?.name || ('card #' + c.card_id)} in ${whose}${c.zone || 'board'} (has ${have})`;
      }
      return `needs ${n} [${c.tag}] in ${whose}${c.zone || 'board'} (has ${have})`;
    }
    return null;
  }


  // Evaluates def's own cost_scaling field (if any, read live off
  // def.effects_json since battle-engine.js has no pre-parse/cache step for
  // card defs): sums each term (tag_count reuses countTagInZone; stat reads
  // playerStat) into a total, then multiplies by 'amount' for a signed
  // delta -- mirrors costModifierDelta's {delta, minimum} return shape so
  // callers can combine both identically. Unlike costModifierDelta (which
  // scans OTHER cards' passives), this reads def's own field, so it only
  // ever applies to the card being played -- callers must never invoke this
  // for Champion casts (cost_scaling intentionally never applies there,
  // matching cost_modifier's existing precedent).
  function dynamicCostDelta(def, ap, op, cardDefs) {
    const cs = def?.effects_json?.cost_scaling;
    if (!cs) return { delta: 0, minimum: 0 };
    let total = 0;
    for (const term of (cs.terms || [])) {
      const p = term.side === 'opponent' ? op : ap;
      total += term.kind === 'stat'
        ? playerStat(p, term.stat)
        : countTagInZone(p, term.tag, term.zone, cardDefs);
    }
    return { delta: total * (cs.amount || 0), minimum: cs.minimum || 0 };
  }

  // count_source lets a card's amount be computed at resolution time instead
  // of fixed on the card -- e.g. "deal damage equal to your [station]
  // permanents in play". zone: 'board' | 'discard' | 'hand', side: 'self' |
  // 'opponent'. typeburst_source (boolean) is the other amount-derivation
  // mode: the count of distinct supertypes across the caster's own board +
  // discard. Resolved once, up front (same point eff.amount was always
  // read), which is what makes a self-referential effect (e.g. "recycle
  // cards equal to [tag] cards in your own discard") correct for free -- the
  // count reflects the zone before this same effect mutates it.
  function resolveAmount(eff, ap, op, cardDefs) {
    if (eff.typeburst_source) return typeburstCount(ap, cardDefs);
    const cs = eff.count_source;
    if (!cs) return eff.amount || 0;
    const p = cs.side === 'opponent' ? op : ap;
    // v4.36: zone 'counters' counts tagged COUNTERS on that player's board
    // rather than cards carrying the tag -- the half that makes a counter a
    // resource other cards can read. Deliberately a ZONE rather than a new
    // field: tag/zone/side already exist on count_source and in the editor, so
    // this needs no new JSON shape and no new control.
    if (cs.zone === 'counters') return countCounters(p, cs.tag);
    // v4.55: "equal to the melee damage dealt" -- the passive owner's own event
    // value, so side and tag do not apply.
    if (cs.zone === 'melee_damage') return ap.meleeDealtEvent || 0;
    return countTagInZone(p, cs.tag, cs.zone, cardDefs);
  }
  // The pool adjust_health / add_melee_counters / add_armor_counters pick
  // from on `side`. A function (v4.70) so only_if_target's guard asks the
  // exact question the effect itself answers.
  function counterStatPool(eff, side, a, cardDefs) {
    return side.board.filter(c => {
      if (c.hp === null || c.destroyed) return false;
      // v4.57: a permanent at 0 hp or below is dead, waiting only for the
      // next prune -- a heal must not lift it back (Ryan, 2026-11-05: "a
      // creature who hits 0 hp should die", creature or structure). It
      // goes to a living target instead. Pierces (a < 0) and counters
      // still see it, so nothing else about targeting moves.
      if (eff.type === 'adjust_health' && a > 0 && c.hp <= 0) return false;
      const def = cardDefs[c.card_id];
      if (eff.tag && !entryHasTag(c, eff.tag, cardDefs)) return false;
      // An ABSENT target_supertype means CREATURE, not "anything with
      // health" -- without the default this pool took every HP-bearing
      // permanent, so Dirty Oven (960), which prints as "an enemy
      // creature", was piercing and shrinking STRUCTURES. Matches
      // counter_desc()'s own default and restrictedEnergyMatch()'s
      // identical rule; "any permanent" stays expressible explicitly.
      if (!matchesSupertype(def, eff.target_supertype || 'creature')) return false;
      if (entryExcluded(c, cardDefs, eff.exclude_tags) || entryLacksRequired(c, cardDefs, eff.only_tags)) return false;
      if (eff.cost_op && !matchesCostFilter(def, '', eff.cost_op, eff.cost_threshold ?? 0)) return false;
      return true;
    });
  }

  // v4.70 only_if_target (Ryan, 2026-11-18): a "pay X, then Y" ability with
  // this flag is not used at all -- no cost paid -- when none of its TARGETED
  // top-level `then` effects would find a target. Targeted means the
  // destroy family (the v4.69 removalPool, indestructible entries not
  // counting) and a counter/health change on either side (v4.71; 4.70
  // counted the opponent's side only), never one on `self`. A
  // `then` with nothing targeted leaves the flag inert. Must match
  // validate.go's / engine.go's onlyIfTargetBlocks and validate.py's
  // only_if_target_blocks.
  const COST_THEN_TYPES = new Set(['utilize_and', 'sacrifice_and', 'pay_honor_and', 'pay_life_and', 'pay_shields_and',
                                   'discard_and', 'recycle_and', 'exile_discard_and', 'spend_counters_and']);
  const COUNTER_STAT_TYPES = new Set(['adjust_health', 'add_melee_counters', 'add_armor_counters']);
  function onlyIfTargetBlocks(eff, ap, op, cardDefs) {
    if (eff.only_if_target !== true || !COST_THEN_TYPES.has(eff.type)) return false;
    let targeted = false;
    for (const sub of (eff.then || [])) {
      if (!sub) continue;
      if (REMOVAL_EFFECT_TYPES.has(sub.type)) {
        targeted = true;
        if (removalPool(sub, op, cardDefs).some(c => !isIndestructible(c, cardDefs))) return false;
      } else if (COUNTER_STAT_TYPES.has(sub.type) && !sub.self) {
        // v4.71: a counter/health change on YOUR OWN side is targeted too
        // (Mighty Mushroom, Treasure Horde) -- same side rule as the effect.
        targeted = true;
        const side = sub.side === 'opponent' ? op : ap;
        if (counterStatPool(sub, side, resolveAmount(sub, ap, op, cardDefs), cardDefs).length) return false;
      }
    }
    return targeted;
  }
  function applyEffectImpl(eff, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan) {
    const a    = resolveAmount(eff, ap, op, cardDefs);
    const tpref = plan?.target_preference ?? 'random';
    const cpref = plan?.cost_preference ?? 'random';
    // Which of YOUR OWN permanents the engine picks. Separate from
    // target_preference (aims at the OPPONENT) and cost_preference (what you
    // spend). Unset falls back to target_preference -- what these effects read
    // before this field existed -- so an untouched battle plan is unchanged.
    // Must match validate.go's spref and validate.py's spref exactly.
    const spref = plan?.self_target_preference || tpref;
    const push = msg => {
      if (!log) return;
      markSrc(log, source);
      emitEvent({
        kind:   'effect',
        type:   eff.type,
        amount: a,
        src:    source && source.card_id != null ? source.card_id : null,
        iid:    source && source.iid != null ? source.iid : null,
        pi:     piOf(ap),
        opp:    piOf(op),
        passive: !!fromPassive,
        text:   msg,
      });
      log.push(msg);
    };
    // v4.72 only_if: a per-ability condition in the play_requires vocabulary
    // ("if you control no Queen"). Unmet -> the whole ability, cost included,
    // does nothing. Checked first, before only_if_target, so a skipped ability
    // never pays anything. Side is relative to ap, the ability's owner.
    if (Array.isArray(eff.only_if) && eff.only_if.length) {
      const why = gateListBlocker(eff.only_if, ap, op, cardDefs);
      if (why) {
        push(`condition not met (${why}) — ability skipped`);
        return;
      }
    }
    // v4.70: checked before ANY cost is paid -- see onlyIfTargetBlocks.
    if (onlyIfTargetBlocks(eff, ap, op, cardDefs)) {
      push(`no target for this ability — nothing paid, effect skipped`);
      return;
    }

    switch (eff.type) {

      case 'gain_energy':
        ap.energy += a;
        push(`+${a} energy (${ap.energy} basic)`);
        break;

      case 'opponent_gain_energy':
        op.energy += a;
        push(`opponent +${a} energy (${op.energy} basic)`);
        break;

      case 'gain_ephemeral_energy':
        ap.ephemeral += a;
        push(`+${a} ephemeral energy (${ap.ephemeral} eph)`);
        break;

      case 'gain_restricted_energy': {
        const tag = eff.tag || '';
        const targetSupertype = eff.target_supertype || '';
        const ephemeral = !!eff.ephemeral;
        const excludeSupertypes = Array.isArray(eff.exclude_supertypes) ? eff.exclude_supertypes.filter(Boolean) : [];
        ap.restrictedEnergy.push({ amount: a, tag, targetSupertype, excludeSupertypes, ephemeral });
        push(excludeSupertypes.length
          ? `+${a} ${ephemeral ? 'ephemeral ' : ''}energy usable except on ${excludeSupertypes.join('/')}`
          : `+${a} ${ephemeral ? 'ephemeral ' : ''}energy restricted to ${tag || targetSupertype || 'creature'}`);
        break;
      }

      case 'gain_life': {
        const amp = a + sumCombatKW(ap.board, 'amplify_life_gain', cardDefs);
        ap.life += amp; tally(ap, 'life_gained', amp);
        push(`+${amp} life → ${ap.life}${amp > a ? ' (amplified)' : ''}`);
        firePassives(ap, 'on_life_gain', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        firePassives(op, 'on_opponent_life_gain', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        break;
      }

      case 'opponent_gain_life': {
        const amp = a + sumCombatKW(ap.board, 'amplify_life_gain', cardDefs);
        op.life += amp; tally(op, 'life_gained', amp);
        push(`opponent +${amp} life → ${op.life}${amp > a ? ' (amplified)' : ''}`);
        firePassives(op, 'on_life_gain', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        firePassives(ap, 'on_opponent_life_gain', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        break;
      }

      // Time Walk (v4.12). Banks additional turns for the player who will take
      // them. extra_turn_opponent is the same mechanism pointed the other way
      // -- a genuine DRAWBACK, handing your opponent a turn.
      //
      // Clamped 1..10 like every other counted effect. No amplify keyword and
      // no on_* passive fires for it: there is no "turn gained" event in the
      // engine, and inventing one would mean a watcher in all four.
      case 'extra_turn': {
        const n = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        ap.extraTurns += n;
        push(`takes ${n} additional turn${n !== 1 ? 's' : ''} after this one`);
        break;
      }
      case 'extra_turn_opponent': {
        const n = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        op.extraTurns += n;
        push(`opponent takes ${n} additional turn${n !== 1 ? 's' : ''} (drawback)`);
        break;
      }
      case 'gain_honor': {
        const amp = a + sumCombatKW(ap.board, 'amplify_honor', cardDefs);
        ap.honor += amp; tally(ap, 'honor_gained', amp);
        ap.honorGainedTotal = (ap.honorGainedTotal || 0) + amp;
        push(`+${amp} honor → ${ap.honor}${amp > a ? ' (amplified)' : ''}`);
        firePassives(ap, 'on_honor_gain', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        firePassives(op, 'on_opponent_honor_gain', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        break;
      }

      // v4.58. gain_honor pointed at the opponent -- a drawback, built exactly
      // like opponent_gain_life: amplified by the CASTER's amplify_honor (the
      // card's own controller decides how big its effects are), credited to
      // the opponent's honor and whole-game total, and the opponent's
      // on_honor_gain fires as if they had gained it themselves.
      case 'opponent_gain_honor': {
        const amp = a + sumCombatKW(ap.board, 'amplify_honor', cardDefs);
        op.honor += amp; tally(op, 'honor_gained', amp);
        op.honorGainedTotal = (op.honorGainedTotal || 0) + amp;
        push(`opponent +${amp} honor → ${op.honor}${amp > a ? ' (amplified)' : ''}`);
        firePassives(op, 'on_honor_gain', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        firePassives(ap, 'on_opponent_honor_gain', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        break;
      }

      case 'lose_honor':
        if (hasPassive(ap, 'prevent_honor_loss', cardDefs)) {
          push(`honor loss blocked by your own passive`);
        } else {
          // Not floored at 0 -- same as reduce_opponent_honor. A self-inflicted
          // "drawback" that's floored at a point where it can never actually
          // hurt you (the -100 dishonor loss condition) isn't a real drawback;
          // confirmed bug 2026-07-25 via Vampiric Aura not being able to
          // contribute toward its own controller's dishonor loss.
          ap.honor -= a; tally(ap, 'honor_lost', a);
          push(`-${a} honor → ${ap.honor}`);
          // v3.98: honor's counterpart to on_life_loss. Fires when honor is
          // TAKEN, never when it is SPENT -- pay_honor_and and the
          // additional_cost honor payment deliberately do not fire it, exactly
          // as paying life does not fire on_life_loss. a > 0 because a negative
          // amount is a gain; inside the prevent_honor_loss else-branch because
          // a prevented loss never happened.
          if (a > 0) {
            firePassives(ap, 'on_honor_loss', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
            firePassives(op, 'on_opponent_honor_loss', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          }
        }
        break;

      case 'gain_shield': {
        if (hasPassive(op, 'prevent_opponent_shield', cardDefs)) {
          push(`shield gain blocked by opponent's passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_shield', cardDefs);
          ap.shields += amp; tally(ap, 'shields_gained', amp);
          push(`+${amp} shields → ${ap.shields}${amp > a ? ' (amplified)' : ''}`);
          firePassives(ap, 'on_shielded', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          firePassives(op, 'on_opponent_shielded', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        }
        break;
      }

      // v4.58. gain_shield pointed at the opponent, mirrored the same way. The
      // blocker is the CASTER's prevent_opponent_shield, because the caster is
      // the gainer's opponent -- "your opponent can't gain shields" holds even
      // when you are the one handing them over.
      case 'opponent_gain_shield': {
        if (hasPassive(ap, 'prevent_opponent_shield', cardDefs)) {
          push(`opponent's shield gain blocked by your own passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_shield', cardDefs);
          op.shields += amp; tally(op, 'shields_gained', amp);
          push(`opponent +${amp} shields → ${op.shields}${amp > a ? ' (amplified)' : ''}`);
          firePassives(op, 'on_shielded', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          firePassives(ap, 'on_opponent_shielded', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        }
        break;
      }

      case 'lose_shield':
        ap.shields = Math.max(0, ap.shields - a);
        push(`-${a} shields → ${ap.shields}`);
        break;

      case 'poison_opponent': {
        if (hasPassive(op, 'prevent_poison', cardDefs)) {
          push(`poison blocked by opponent's passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_poison', cardDefs);
          op.poison += amp; tally(op, 'poison_gained', amp);
          push(`opponent +${amp} poison → ${op.poison} poison${amp > a ? ' (amplified)' : ''}`);
          firePassives(op, 'on_poisoned', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          firePassives(ap, 'on_opponent_poisoned', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        }
        break;
      }

      case 'gain_poison': {
        if (hasPassive(ap, 'prevent_poison', cardDefs)) {
          push(`poison blocked by your own passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_poison', cardDefs);
          ap.poison += amp; tally(ap, 'poison_gained', amp);
          push(`+${amp} poison → ${ap.poison} poison${amp > a ? ' (amplified)' : ''}`);
          firePassives(ap, 'on_poisoned', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          firePassives(op, 'on_opponent_poisoned', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        }
        break;
      }

      case 'remove_poison': {
        // v3.93: literal 0 cures ALL poison (the remove_opponent_energy
        // sentinel). Mirrors validate.go.
        const cureAll = literalZeroMeansAll(eff);
        const removed = cureAll ? ap.poison : Math.min(a, ap.poison);
        ap.poison = cureAll ? 0 : Math.max(0, ap.poison - a);
        tally(ap, 'poison_cured', removed);
        push(cureAll ? `cured all ${removed} poison` : `-${removed} poison → ${ap.poison} poison`);
        break;
      }

      case 'shuffle_discard_to_library': {
        // A literal 0 recycles the ENTIRE discard pile, same sentinel as
        // remove_poison / remove_opponent_energy. Literal only -- a count_source
        // resolving to 0 must recycle nothing. amplify_recycle is not applied on
        // the all-branch: nothing is above "all". See validate.go.
        const amp = a + sumCombatKW(ap.board, 'amplify_recycle', cardDefs);
        const count = literalZeroMeansAll(eff)
          ? ap.discard.length
          : Math.min(amp, ap.discard.length);
        // This always operates on `ap`'s own discard/library -- but when fired
        // from an on_leave trigger on a permanent an opponent just destroyed,
        // `ap` here is that permanent's owner, not whoever's turn is active.
        // Without naming the source card + whose discard/library it is, the
        // log line reads as if it happened to the active player, when it may
        // have actually happened to their opponent (reported by Tristal: "I
        // blew up an opposing [Tristal's Bauble] and the log said I recycled
        // a card" -- the mechanic was correct, only the log was ambiguous).
        const srcName = source ? (cardDefs[source.card_id]?.name || source.card_id) : null;
        const label = srcName ? `${srcName}: ` : '';
        // `if (!count)` wrongly treated a negative count (amount + a negative
        // amplify_recycle bonus, e.g. 1 + -2) as truthy (only 0 is falsy in
        // JS), then `.slice(0, count)`/`.slice(count)` with a negative index
        // sliced "all but the last N" instead of nothing -- collapsed the
        // whole discard pile into the library on a single card. Go already
        // guards with `count > 0` (found via the fake-card regression sweep,
        // 2026-08-06; same bug independently in validate.py, fixed there too).
        if (count <= 0) { push(`${label}discard empty, nothing to shuffle in`); break; }
        const shuffledDiscard = shuffle(ap.discard, rand);
        const picked = shuffledDiscard.slice(0, count);
        ap.discard = shuffledDiscard.slice(count);
        ap.deck = shuffle([...ap.deck, ...picked], rand);
        // The DOMINANT recycle effect: 56 live cards print "Recycle" through
        // shuffle_discard_to_library against 7 across all three recycle_*
        // effects combined. Instrumenting only the recycle_* cases would have
        // left cards_recycled counting almost nothing, and nothing would have
        // said so -- a gate that never opens looks exactly like a card that
        // simply had nothing to recycle.
        tally(ap, 'cards_recycled', count);
        push(`${label}shuffled ${count} card${count !== 1 ? 's' : ''} from its owner's discard into their library (${ap.deck.length} in library)${amp > a ? ' (amplified)' : ''}`);
        fireRecycle(ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        break;
      }

      case 'draw_card': {
        const base = Math.max(a, 1);
        const amp  = base + sumCombatKW(ap.board, 'amplify_draw', cardDefs);
        let drewCount = 0, ranDry = false;
        for (let di = 0; di < amp; di++) {
          if (!tryDraw(ap)) { ranDry = true; break; }
          drewCount++;
        }
        if (drewCount > 0) {
          push(`drew ${drewCount} card${drewCount !== 1 ? 's' : ''} (${ap.hand.length} in hand)${amp > base ? ' (amplified)' : ''}`);
          // A PARTIAL draw that empties the library used to log only the cards
          // it managed to draw, so the deck-out that ends the game right after
          // was invisible -- e.g. Arcane Surge draws 4 with 2 cards left, the
          // log said "drew 2 cards" and the player simply lost, with nothing
          // explaining why. (reported: Fry Guy, 2026-09-01)
          if (ranDry) push(`tried to draw ${amp - drewCount} more — deck empty`);
          firePassives(ap, 'on_draw', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          // Every card drawn by an EFFECT is an extra draw by definition: the
          // turn's own draw is step 1 and fires its own passives there.
          for (let xd = 0; xd < drewCount; xd++)
            firePassives(ap, 'on_extra_draw', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          // v4.53: the mirror, on the OTHER board -- once per extra card.
          for (let xd = 0; xd < drewCount; xd++)
            firePassives(op, 'on_opponent_extra_draw', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          firePassives(op, 'on_opponent_draw', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        } else {
          push(`tried to draw — deck empty`);
        }
        break;
      }

      case 'opponent_draw_card': {
        const base = Math.max(a, 1);
        const amp  = base + sumCombatKW(ap.board, 'amplify_draw', cardDefs);
        let drewCount = 0, ranDry = false;
        for (let di = 0; di < amp; di++) {
          if (!tryDraw(op)) { ranDry = true; break; }
          drewCount++;
        }
        if (drewCount > 0) {
          push(`opponent drew ${drewCount} card${drewCount !== 1 ? 's' : ''} (${op.hand.length} in hand)${amp > base ? ' (amplified)' : ''}`);
          if (ranDry) push(`tried to draw ${amp - drewCount} more for opponent — deck empty`);
          firePassives(op, 'on_draw', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          for (let xd = 0; xd < drewCount; xd++)
            firePassives(op, 'on_extra_draw', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          for (let xd = 0; xd < drewCount; xd++)
            firePassives(ap, 'on_opponent_extra_draw', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          firePassives(ap, 'on_opponent_draw', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        } else {
          push(`tried to draw for opponent — deck empty`);
        }
        break;
      }

      case 'mill_opponent': {
        if (!op.deck.length) { push(`tried to mill — opponent deck empty`); break; }
        if (hasPassive(op, 'prevent_mill', cardDefs)) {
          push(`mill prevented by opponent's passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_mill', cardDefs);
          // Floored at 0: amp (and thus millCount) can be negative when a
          // card's amplify_mill amount is negative (sumCombatKW correctly
          // leaves it as-is). The mill loop below already no-ops for a
          // negative count, but without this floor milledOpponentTotal
          // would still get decremented despite zero cards actually
          // moving -- found via the fake-card sweep at full scale.
          let millCount = Math.max(0, Math.min(amp, op.deck.length));   // let: the loop below corrects it
          // The deck can EMPTY mid-loop: arriveInDiscard fires on_discard
          // watchers, which run arbitrary effects -- a draw among them -- and
          // those consume the very deck this loop is walking, while millCount
          // was clamped once before any of it. shift() on an empty array
          // returns undefined, so JS did not crash here the way Go did: it
          // passed undefined on as a card id. Found 2026-10-15 by the
          // whole-catalog sweep. Count actual mills so the total and the log
          // stay honest when the break happens.
          let milledN = 0;
          for (let mi = 0; mi < millCount; mi++) {
            if (!op.deck.length) break;
            milledN++;
            arriveInDiscard(op, op.deck.shift(), cardDefs, rand, log, 'deck', ap, opPlan, plan);
          }
          millCount = milledN;
          ap.milledOpponentTotal = (ap.milledOpponentTotal || 0) + millCount;
          // The MILLED player is the one whose cards moved, so the counter
          // goes on op -- a gate reading cards_milled with side:opponent then
          // means "their deck was milled", matching how on_mill /
          // on_opponent_mill already split.
          tally(op, 'cards_milled', millCount);
          push(`milled opponent ${millCount} card${millCount !== 1 ? 's' : ''} (${op.deck.length} remain)${amp > a ? ' (amplified)' : ''}`);
          // on_mill/on_opponent_mill only fire when a card was actually
          // milled -- matches shell/validate.go and shell/validate.py's
          // `millCount > 0` guard. The early `!op.deck.length` bail above
          // only covers a completely empty deck; amp can still compute to
          // 0 (or the deck can have fewer cards than amp) leaving millCount
          // at 0 with a nonempty deck, and this branch used to fire the
          // passives anyway -- a real divergence found via the fake-card
          // regression sweep (Go/Python agreed with each other; only JS
          // fired an extra on_mill here, consuming extra RNG calls for the
          // rest of the match).
          if (millCount > 0) {
            firePassives(op, 'on_mill', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
            firePassives(ap, 'on_opponent_mill', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          }
        }
        break;
      }

      case 'mill_self': {
        if (!ap.deck.length) { push(`tried to mill self — deck empty`); break; }
        if (hasPassive(ap, 'prevent_mill', cardDefs)) {
          push(`mill prevented by your own passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_mill', cardDefs);
          let millCount = Math.min(amp, ap.deck.length);   // let: the loop below corrects it
          // The deck can EMPTY mid-loop: arriveInDiscard fires on_discard
          // watchers, which run arbitrary effects -- a draw among them -- and
          // those consume the very deck this loop is walking, while millCount
          // was clamped once before any of it. shift() on an empty array
          // returns undefined, so JS did not crash here the way Go did: it
          // passed undefined on as a card id. Found 2026-10-15 by the
          // whole-catalog sweep. Count actual mills so the total and the log
          // stay honest when the break happens.
          let milledSelfN = 0;
          for (let mi = 0; mi < millCount; mi++) {
            if (!ap.deck.length) break;
            milledSelfN++;
            arriveInDiscard(ap, ap.deck.shift(), cardDefs, rand, log, 'deck', op, plan, opPlan);
          }
          millCount = milledSelfN;
          tally(ap, 'cards_milled', millCount);
          push(`milled self ${millCount} card${millCount !== 1 ? 's' : ''} (${ap.deck.length} remain)${amp > a ? ' (amplified)' : ''}`);
          // Same millCount>0 guard as mill_opponent above.
          if (millCount > 0) {
            firePassives(ap, 'on_mill', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
            firePassives(op, 'on_opponent_mill', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          }
        }
        break;
      }

      // Exile variant of mill_opponent -- same prevent_mill/on_mill/
      // on_opponent_mill/milledOpponentTotal handling (it's still "removing
      // cards from the top of the opponent's library" for those purposes),
      // only the removed cards go nowhere instead of to op.discard.
      case 'exile_opponent_deck': {
        if (!op.deck.length) { push(`tried to exile from library — opponent deck empty`); break; }
        if (hasPassive(op, 'prevent_mill', cardDefs)) {
          push(`exile prevented by opponent's passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_mill', cardDefs);
          // Same negative-amplify_mill floor as mill_opponent above.
          const exileCount = Math.max(0, Math.min(amp, op.deck.length));
          for (let mi = 0; mi < exileCount; mi++) op.deck.shift();
          ap.milledOpponentTotal = (ap.milledOpponentTotal || 0) + exileCount;
          tally(op, 'cards_exiled', exileCount);
        push(`exiled ${exileCount} card${exileCount !== 1 ? 's' : ''} from the top of opponent's library (${op.deck.length} remain)${amp > a ? ' (amplified)' : ''}`);
          // Same millCount>0 guard as mill_opponent above.
          if (exileCount > 0) {
            firePassives(op, 'on_mill', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
            firePassives(ap, 'on_opponent_mill', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          }
        }
        break;
      }

      // v4.25. The self-side twin of exile_opponent_deck, the way mill_self
      // twins mill_opponent. A DRAWBACK: these cards are gone for good, so it
      // cannot be answered by anything that recurs from a discard pile.
      // Mirrors mill_self exactly -- same prevent_mill check against the
      // OWNER, same amplify_mill, same on_mill / on_opponent_mill pair named
      // from the milled player's side -- except the cards leave play entirely
      // instead of arriving in the discard.
      case 'exile_self_deck': {
        if (!ap.deck.length) { push(`tried to exile from own library — deck empty`); break; }
        if (hasPassive(ap, 'prevent_mill', cardDefs)) {
          push(`exile prevented by your own passive`);
        } else {
          const amp = a + sumCombatKW(ap.board, 'amplify_mill', cardDefs);
          const exileCount = Math.max(0, Math.min(amp, ap.deck.length));
          for (let mi = 0; mi < exileCount; mi++) ap.deck.shift();
          tally(ap, 'cards_exiled', exileCount);
        push(`exiled ${exileCount} card${exileCount !== 1 ? 's' : ''} from the top of your own library (${ap.deck.length} remain)${amp > a ? ' (amplified)' : ''}`);
          if (exileCount > 0) {
            firePassives(ap, 'on_mill', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
            firePassives(op, 'on_opponent_mill', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          }
        }
        break;
      }

      // v4.39. Exile N from the top of a library and manifest N copies of a
      // named card in their place, so the library comes out the SAME SIZE and
      // full of something else. The two halves exist separately already
      // (exile_opponent_deck, manifest_card with destination:deck) but cannot
      // be composed: the manifest count has to be the number actually exiled,
      // and count_source would read the zone AFTER the exile emptied it.
      //
      // Deliberately NOT clamped by MAX_TOKEN_COPIES_PER_FIRE. That cap stops a
      // card conjuring an unbounded pile from nothing; here the count is already
      // bounded by the library this effect just emptied, so it can never put
      // back more than it took. Clamping it would silently turn a replacement
      // into a deck-out kill -- exile 40, hand back 10.
      //
      // Mill rules are inherited WHOLE from exile_opponent_deck: prevent_mill
      // stops it (and then nothing is manifested either -- the exile is the
      // thing being prevented), amplify_mill raises N with the refill following
      // the amplified number so the 1:1 promise holds, and on_mill /
      // on_opponent_mill fire. A player reading "my library is protected"
      // should not have to learn an exception.
      case 'exile_deck_and_manifest': {
        const xOpp   = (eff.side ?? 'opponent') !== 'own';
        const xTo    = xOpp ? op : ap, xFoe = xOpp ? ap : op;
        const xPlan  = xOpp ? opPlan : plan, xFoePlan = xOpp ? plan : opPlan;
        const xWho   = xOpp ? "opponent's" : 'your own';
        if (!xTo.deck.length) { push(`tried to exile from ${xWho} library — deck empty`); break; }
        if (hasPassive(xTo, 'prevent_mill', cardDefs)) {
          push(`exile prevented by ${xOpp ? "opponent's" : 'your own'} passive`);
          break;
        }
        const xAmp = a + sumCombatKW(ap.board, 'amplify_mill', cardDefs);
        // literal 0 = the WHOLE library, the same sentinel five other effects
        // use. An explicit N is floored at 0 (a negative amplify_mill) and
        // capped by what is actually there.
        const xN = literalZeroMeansAll(eff)
          ? xTo.deck.length
          : Math.max(0, Math.min(xAmp, xTo.deck.length));
        if (!xN) { push(`nothing to exile from ${xWho} library`); break; }
        for (let xi = 0; xi < xN; xi++) xTo.deck.shift();
        if (xOpp) ap.milledOpponentTotal = (ap.milledOpponentTotal || 0) + xN;
        // The exile has HAPPENED by here. A missing def costs the library those
        // cards and puts nothing back, which is why the id is checked before
        // the refill and reported rather than silently skipped.
        const xDef = cardDefs[eff.card_id];
        if (!xDef) {
          push(`  no card found for card_id ${eff.card_id} to manifest — ${xN} card${xN !== 1 ? 's' : ''} exiled with no replacement`);
        } else {
          refillDeck(xTo, eff.card_id, xN, Math.max(0, Math.floor(eff.band) || 0), rand);
          const xName = xDef.name || eff.card_id;
          tally(xTo, 'cards_exiled', xN);
        push(`exiled ${xN} card${xN !== 1 ? 's' : ''} from the top of ${xWho} library and manifested ${xN}x ${xName} in their place (${xTo.deck.length} in library)${xAmp > a && !literalZeroMeansAll(eff) ? ' (amplified)' : ''}`);
        }
        // Named from the milled player's side, exactly as exile_opponent_deck
        // and exile_self_deck both do.
        firePassives(xTo, 'on_mill', xTo, xFoe, rand, cardDefs, log, fromPassive, xPlan, xFoePlan);
        firePassives(xFoe, 'on_opponent_mill', xFoe, xTo, rand, cardDefs, log, fromPassive, xFoePlan, xPlan);
        break;
      }

      case 'discard_opponent': {
        // amount=0 means discard the whole hand -- same sentinel convention
        // as remove_opponent_energy.
        const n = literalZeroMeansAll(eff) ? op.hand.length : Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let discarded = 0;
        for (let k = 0; k < n && op.hand.length; k++) {
          // v4.06: the VICTIM chooses what to pitch, via their own plan --
          // opPlan, not plan. The caster still cannot steer it (the comment on
          // recycle_opponent is about the CASTER's preference and still
          // holds); what changed is that the defender is no longer choosing at
          // random on a player's behalf.
          const idx = pickDiscardFrom(op.hand.map((_, ci) => ci), op.hand, cardDefs, opPlan, rand);
          if (idx < 0) break;
          arriveInDiscard(op, op.hand.splice(idx, 1)[0], cardDefs, rand, log, 'hand', ap, opPlan, plan);
          discarded++;
        }
        push(discarded ? `opponent discarded ${discarded} card${discarded !== 1 ? 's' : ''} (${op.hand.length} in hand)` : `opponent has no cards to discard`);
        break;
      }

      case 'discard_self': {
        const n = literalZeroMeansAll(eff) ? ap.hand.length : Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let discarded = 0;
        for (let k = 0; k < n && ap.hand.length; k++) {
          const idx = pickDiscardIdx(ap.hand, cardDefs, plan, rand);
          arriveInDiscard(ap, ap.hand.splice(idx, 1)[0], cardDefs, rand, log, 'hand', op, plan, opPlan);
          discarded++;
        }
        push(discarded ? `discarded ${discarded} card${discarded !== 1 ? 's' : ''} (${ap.hand.length} in hand)` : `no cards to discard`);
        break;
      }

      case 'recycle_self': {
        // Simple, direct, partial-tolerant recycle (as opposed to recycle_and's
        // all-or-nothing cost-payment gate) -- mirrors discard_self's shape.
        // amount=0 means recycle the entire discard pile.
        // v3.83: see validate.go's recycle_self. only_tags/exclude_tags/
        // target_supertype build the candidate pool, so amount=0 means "all
        // MATCHING cards"; unfiltered behaviour is unchanged.
        let pool = ap.discard.filter(cid =>
          !hasExcludedTag(cardDefs, cid, eff.exclude_tags)
          && !lacksRequiredTag(cardDefs, cid, eff.only_tags)
          && (!eff.target_supertype || matchesSupertype(cardDefs[cid], eff.target_supertype)));
        const n = Math.min(literalZeroMeansAll(eff) ? pool.length : Math.min(Math.max(Math.floor(a) || 1, 1), 10), pool.length);
        if (n > 0) {
          const picked = [];
          for (let i = 0; i < n; i++) {
            const cid = selectPileCard(pool, cpref, cardDefs, rand);
            pool.splice(pool.indexOf(cid), 1);
            picked.push(cid);
          }
          // One discard entry removed per picked card -- the pre-filter code
          // assigned the leftover pool straight back, which with a filtered
          // pool would drop every non-matching card.
          const rem = ap.discard.slice();
          for (const cid of picked) rem.splice(rem.indexOf(cid), 1);
          ap.discard = rem;
          ap.deck = shuffle([...ap.deck, ...picked], rand);
          tally(ap, 'cards_recycled', n);
          push(`recycled ${n} card${n !== 1 ? 's' : ''} from discard into library`);
          fireRecycle(ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        } else {
          push(`no cards in discard to recycle`);
        }
        break;
      }

      case 'destroy_self': {
        // Self-targeted sibling of sacrifice_own_permanent -- see validate.go.
        // Deterministic (no candidate pick), self-inflicted so indestructible
        // does not apply, and a no-op with no source or an already-claimed one.
        if (source && !source.destroyed) {
          source.destroyed = true;
          push(`${cardDefs[source.card_id]?.name || source.card_id} destroyed itself`);
          fireTriggerOn(source, 'on_death', ap, op, rand, cardDefs, log, plan, opPlan);
          fireTriggerOn(source, 'on_leave', ap, op, rand, cardDefs, log, plan, opPlan);
          ap.board = ap.board.filter(c => c !== source);
          if (!championLeave(ap, source.card_id, log, cardDefs)) toDiscardOrExile(ap, source, cardDefs, rand, log, op, plan, opPlan);
        }
        break;
      }

      case 'recycle_opponent': {
        // Same shape as recycle_self, but targets the opponent's own discard
        // pile -> their own deck. Random selection (no cost_preference lookup)
        // -- mirrors discard_opponent/exile_opponent_discard's convention that
        // opponent-facing pile/hand picks are always blind, never steered by
        // the caster's own battle-plan preference.
        const n = Math.min(literalZeroMeansAll(eff) ? op.discard.length : Math.min(Math.max(Math.floor(a) || 1, 1), 10), op.discard.length);
        if (n > 0) {
          const shuf = shuffle(op.discard, rand);
          const picked = shuf.slice(0, n);
          op.discard = shuf.slice(n);
          op.deck = shuffle([...op.deck, ...picked], rand);
          tally(op, 'cards_recycled', n);
          push(`recycled ${n} card${n !== 1 ? 's' : ''} from opponent's discard into their library`);
          // Their library grew, so they are the one it happened to.
          fireRecycle(op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        } else {
          push(`no cards in opponent's discard to recycle`);
        }
        break;
      }

      // Exile variant of discard_opponent -- same random-pick-from-hand
      // shape, only the removed card goes nowhere instead of to op.discard.
      case 'exile_opponent_hand': {
        const n = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let exiled = 0;
        for (let k = 0; k < n && op.hand.length; k++) {
          const idx = Math.floor(rand() * op.hand.length);
          op.hand.splice(idx, 1);
          exiled++;
        }
        tally(op, 'cards_exiled', exiled);
        push(exiled ? `exiled ${exiled} card${exiled !== 1 ? 's' : ''} from opponent's hand (${op.hand.length} in hand)` : `opponent has no cards to exile`);
        break;
      }

      // v4.26: the self-side twin of exile_opponent_hand, completing the
      // exile family's self/opponent pairs.
      //
      // Picks via pickDiscardIdx, NOT at random: it is your own hand, so the
      // battle plan chooses which card goes, exactly as discard_self does.
      // The opponent version picks randomly only because that hand is hidden.
      //
      // Clamped 1..10 like exile_opponent_hand, and deliberately WITHOUT
      // discard_self's "amount 0 = whole hand" sentinel: parity here means
      // parity with the effect it twins, and "exile your entire hand" is a
      // much larger primitive than this asymmetry was hiding.
      case 'exile_self_hand': {
        const n = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let exiled = 0;
        for (let k = 0; k < n && ap.hand.length; k++) {
          const idx = pickDiscardIdx(ap.hand, cardDefs, plan, rand);
          ap.hand.splice(idx, 1);
          exiled++;
        }
        tally(ap, 'cards_exiled', exiled);
        push(exiled ? `exiled ${exiled} card${exiled !== 1 ? 's' : ''} from your own hand (${ap.hand.length} in hand)` : `no cards in hand to exile`);
        break;
      }

      case 'exile_opponent_discard': {
        // v3.91: literal amount=0 exiles every MATCHING card (mirror of
        // recycle_opponent), and only_tags/exclude_tags/target_supertype now
        // filter the pool as they do for recycle_self. Picks stay random --
        // steering them would change what nine live cards exile.
        const pool = discardPoolFor(eff, op.discard, cardDefs);
        const n = literalZeroMeansAll(eff) ? pool.length : Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        const exileCount = Math.min(n, pool.length);
        if (!exileCount) { push(`opponent's discard pile has nothing matching to exile`); break; }
        const picked = shuffle(pool, rand).slice(0, exileCount);
        op.discard = removeOneEach(op.discard, picked);
        tally(op, 'cards_exiled', exileCount);
        push(`exiled ${exileCount} card${exileCount !== 1 ? 's' : ''} from opponent's discard pile (${op.discard.length} remain)`);
        break;
      }

      // Drawback effect: exiles from your OWN discard pile (as opposed to
      // the exile_discard additional_cost, which is paid to play the card
      // in the first place -- this is a "then also" self-hurting effect).
      case 'exile_self_discard': {
        // v3.91: literal amount=0 exiles every MATCHING card; filters as above.
        const pool = discardPoolFor(eff, ap.discard, cardDefs);
        const n = literalZeroMeansAll(eff) ? pool.length : Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        const exileCount = Math.min(n, pool.length);
        if (!exileCount) { push(`discard pile has nothing matching to exile`); break; }
        const picked = shuffle(pool, rand).slice(0, exileCount);
        ap.discard = removeOneEach(ap.discard, picked);
        tally(ap, 'cards_exiled', exileCount);
        push(`exiled ${exileCount} card${exileCount !== 1 ? 's' : ''} from your own discard pile (drawback)`);
        break;
      }

      case 'reduce_opponent_honor':
        if (hasPassive(op, 'prevent_honor_loss', cardDefs)) {
          push(`honor reduction blocked by opponent's passive`);
        } else {
          // v4.49 Amplify Dishonor. Read off the STRIPPER's board, exactly the
          // shape amplify_damage/amplify_poison/amplify_mill use, and a
          // deliberately separate keyword from amplify_honor -- that one reads
          // at gain_honor and covers only a player's own gains, which is the
          // opposite side of the same resource.
          //
          // The prevent check stays AHEAD of it: prevent_honor_loss blocks the
          // whole reduction before any amount is read, so amplifying it is
          // still zero. Same as amplify_mill against prevent_mill.
          const amp = a + sumCombatKW(ap.board, 'amplify_dishonor', cardDefs);
          // Not floored at 0 -- this is the "attack their honor" effect, the
          // intended path to the dishonor loss condition (honor <= -100 in
          // checkWin). lose_honor (self-inflicted, above) stays floored.
          op.honor -= amp; tally(op, 'honor_lost', amp);
          ap.opponentHonorLostTotal = (ap.opponentHonorLostTotal || 0) + amp;
          push(`opponent -${amp} honor → ${op.honor}${amp > a ? ' (amplified)' : ''}`);
          // Gated on the AMPLIFIED number, so a 0-amount strip that an
          // amplifier turns into a real one still fires the watchers. With no
          // amplifier on the board amp === a, so every existing card behaves
          // byte for byte as it did.
          if (amp > 0) {
            firePassives(op, 'on_honor_loss', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
            firePassives(ap, 'on_opponent_honor_loss', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          }
        }
        break;

      case 'deal_damage_to_player': {
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        const blocked = Math.min(op.shields, amp);
        const lost = dmgPlayer(op, amp, 'damage');
        if (blocked > 0)
          push(`dealt ${amp} to opponent player (${blocked} blocked by shields) → ${op.life} life, ${op.shields} shields left${amp > a ? ' (amplified)' : ''}`);
        else
          push(`dealt ${amp} to opponent player → ${op.life} life${amp > a ? ' (amplified)' : ''}`);
        if (lost > 0) {
          firePassives(op, 'on_life_loss', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
          firePassives(ap, 'on_opponent_life_loss', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        }
        firePassives(ap, 'on_damage_dealt', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        firePassives(op, 'on_opponent_damage_dealt', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        break;
      }

      case 'deal_damage_to_self': {
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        const blocked = Math.min(ap.shields, amp);
        const lost = dmgPlayer(ap, amp, 'self');
        if (blocked > 0)
          push(`dealt ${amp} to self (${blocked} blocked by shields) → ${ap.life} life, ${ap.shields} shields left${amp > a ? ' (amplified)' : ''}`);
        else
          push(`dealt ${amp} to self → ${ap.life} life${amp > a ? ' (amplified)' : ''}`);
        // Same instance is both "player who lost life" and "player who dealt damage"
        // -- both passives fire on ap, unlike deal_damage_to_player where they always
        // land on two different players.
        if (lost > 0) {
          firePassives(ap, 'on_life_loss', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
          firePassives(op, 'on_opponent_life_loss', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        }
        firePassives(ap, 'on_damage_dealt', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        firePassives(op, 'on_opponent_damage_dealt', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan);
        break;
      }

      case 'deal_damage_to_random_creature': {
        // Previously matched any damageable permanent (any entry with hp
        // set), not just Creatures -- Structures/Relics with a health stat
        // (e.g. Arcane Pylon) could get hit by "random creature" effects
        // like Bone Imp's, contradicting the card text. Now filters to
        // supertype Creature, matching Go/Python.
        const ts = op.board.filter(c => !c.destroyed && c.hp !== null && c.hp > 0 && matchesSupertype(cardDefs[c.card_id], 'creature') && !isUntargetable(c, cardDefs));
        if (!ts.length) { push(`no damageable targets`); break; }
        const t = eff.target_highest_cost ? selectHighestCostTarget(ts, cardDefs, rand) : selectTarget(filterByTagOrder(ts, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
        if (isIndestructible(t, cardDefs)) { push(`${cardDefs[t.card_id]?.name || t.card_id} is indestructible — no damage`); break; }
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        const absR = dmgCreature(t, amp);
        push(`dealt ${amp} to ${cardDefs[t.card_id]?.name || t.card_id}${absR > 0 ? ` (${absR} absorbed by armor)` : ''} → ${t.hp} hp${amp > a ? ' (amplified)' : ''}`);
        break;
      }

      // Self-targeted twin of deal_damage_to_random_creature -- used by the
      // Chaos Arena "self_creature_damage" modifier (a random creature you
      // control, not the opponent's).
      case 'deal_damage_to_own_random_creature': {
        const ts = ap.board.filter(c => !c.destroyed && c.hp !== null && c.hp > 0 && matchesSupertype(cardDefs[c.card_id], 'creature') && !isUntargetable(c, cardDefs));
        if (!ts.length) { push(`no damageable targets`); break; }
        const t = eff.target_highest_cost ? selectHighestCostTarget(ts, cardDefs, rand) : selectTarget(filterByTagOrder(ts, plan?.target_tag_order, cardDefs), spref, rand, cardDefs);
        if (isIndestructible(t, cardDefs)) { push(`${cardDefs[t.card_id]?.name || t.card_id} is indestructible — no damage`); break; }
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        const absSelf = dmgCreature(t, amp);
        push(`dealt ${amp} to own ${cardDefs[t.card_id]?.name || t.card_id}${absSelf > 0 ? ` (${absSelf} absorbed by armor)` : ''} → ${t.hp} hp${amp > a ? ' (amplified)' : ''}`);
        break;
      }

      case 'deal_damage_to_weakest_creature': {
        const ts = op.board.filter(c => !c.destroyed && c.hp !== null && c.hp > 0 && matchesSupertype(cardDefs[c.card_id], 'creature') && !isUntargetable(c, cardDefs)).sort((x, y) => {
          const xs = cardDefs[x.card_id]?.stats_json ?? {};
          const ys = cardDefs[y.card_id]?.stats_json ?? {};
          return ((xs.health || 0) + (xs.armor || 0)) - ((ys.health || 0) + (ys.armor || 0));
        });
        if (!ts.length) { push(`no damageable targets`); break; }
        if (isIndestructible(ts[0], cardDefs)) { push(`${cardDefs[ts[0].card_id]?.name || ts[0].card_id} is indestructible — no damage`); break; }
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        const absW = dmgCreature(ts[0], amp);
        push(`dealt ${amp} to weakest ${cardDefs[ts[0].card_id]?.name || ts[0].card_id}${absW > 0 ? ` (${absW} absorbed by armor)` : ''} → ${ts[0].hp} hp${amp > a ? ' (amplified)' : ''}`);
        break;
      }

      case 'destroy_target': {
        // Destroy removes any permanent; on_leave fires, on_death does NOT
        // (not killed) -- except for a token (fireLeaveTrigger), which fires
        // on_death too since it has no discard-pile afterlife to fall back on.
        // .destroyed excluded here too (same protocol as destroy_tagged/
        // destroy_all/destroy_permanents_by_cost/sacrifice_*, v3.58/v3.60)
        // -- without it, this effect firing from within an in-flight
        // mass-destroy cascade's on_death chain could re-select and
        // re-kill (or select as the sole target) an entry that cascade
        // already claimed but hasn't yet physically removed from the
        // board array (confirmed live: an on-death destroy_target
        // re-selecting a Death Arbiter's already-destroyed victim,
        // 2026-08-03).
        const dtPool = narrowByTypeAndCost(op.board.filter(c => !c.destroyed && !isUntargetable(c, cardDefs)), eff, cardDefs);
        if (!dtPool.length) { push(`no targetable permanents to destroy`); break; }
        const t     = eff.target_highest_cost ? selectHighestCostTarget(dtPool, cardDefs, rand) : selectTarget(filterByTagOrder(dtPool, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
        const tName = cardDefs[t.card_id]?.name || t.card_id;
        if (isIndestructible(t, cardDefs)) { push(`${tName} is indestructible — not destroyed`); break; }
        t.destroyed = true;
        push(`destroyed ${tName}`);
        fireLeaveTrigger(t, op, ap, rand, cardDefs, log, undefined, opPlan, plan);
        op.board = op.board.filter(c => c !== t);
        if (!championLeave(op, t.card_id, log, cardDefs)) toDiscardOrExile(op, t, cardDefs, rand, log, ap, opPlan, plan);
        break;
      }

      case 'kill_target': {
        // Kill only works on permanents with a health stat; untargetable are immune
        const ts = narrowByTypeAndCost(op.board.filter(c => !c.destroyed && c.hp !== null && !isUntargetable(c, cardDefs)), eff, cardDefs);
        if (!ts.length) { push(`no killable targets`); break; }
        const t     = eff.target_highest_cost ? selectHighestCostTarget(ts, cardDefs, rand) : selectTarget(filterByTagOrder(ts, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
        const tName = cardDefs[t.card_id]?.name || t.card_id;
        if (isIndestructible(t, cardDefs)) { push(`${tName} is indestructible — not killed`); break; }
        t.destroyed = true;
        push(`killed ${tName}`);
        fireTriggerOn(t, 'on_death', op, ap, rand, cardDefs, log, opPlan, plan);
        fireTriggerOn(t, 'on_leave', op, ap, rand, cardDefs, log, opPlan, plan);
        op.board = op.board.filter(c => c !== t);
        if (!championLeave(op, t.card_id, log, cardDefs)) toDiscardOrExile(op, t, cardDefs, rand, log, ap, opPlan, plan);
        break;
      }

      case 'stun_permanent': {
        // v4.19. Taps a permanent and makes it skip its owner's next `amount`
        // untaps. Three things worth knowing before editing this:
        //
        // 1. `amount` is the stun DURATION, not a target count and NOT the
        //    "a literal 0 means ALL" sentinel five other effects use. Stun 0
        //    is a real, useful value -- tapped for the opponent's turn, then
        //    untapping normally -- and a missing amount coerces to exactly
        //    that, which is the right default. Never route it through
        //    literalZeroMeansAll().
        // 2. It does NOT fire on_utilized / on_opponent_utilized. Those mean
        //    "the owner spent this as a cost"; a stun is something done TO the
        //    permanent. Firing them would make an enemy stun pay out the
        //    defender's own utilize rewards, and would turn the self-stun
        //    drawback into a combo enabler.
        // 3. Stacking is additive, per kkoechel. Two Stun 0s therefore still
        //    total 0 counters, which is consistent: Stun N means "skip N
        //    untaps", and 0 + 0 skips none.
        const stunTo   = eff.side === 'own' ? ap : op;
        const stunFilt = eff.target_supertype || 'permanent';
        const stunPool = stunTo.board.filter(e => {
          if (e.destroyed) return false;
          const d = cardDefs[e.card_id];
          // tag takes precedence over supertype -- the same either/or rule
          // return_opponent_permanent and anthemBonus use.
          // entryHasTag, not d.tags: it also sees the synthetic 'token' tag a
          // conjured entry carries, which a raw def lookup misses -- that gap
          // would make this engine disagree with Go on every token.
          const m = eff.tag ? entryHasTag(e, eff.tag, cardDefs) : matchesSupertype(d, stunFilt);
          if (!m) return false;
          // v4.20: an OPTIONAL cost gate, the same shape destroy_target and
          // destroy_random_tagged use. Applied ONLY when cost_op is actually
          // set -- defaulting the operator would silently narrow every stun
          // card that never asked for one. '' as the supertype means "no
          // supertype constraint", because the tag/supertype test above has
          // already decided membership; passing stunFilt here would re-apply
          // it and make a tag-filtered stun also require a supertype match.
          if (eff.cost_op && !matchesCostFilter(d, '', eff.cost_op, eff.cost_threshold || 0)) return false;
          return !isUntargetable(e, cardDefs) && !isUnstunnable(e, cardDefs);
        });
        if (!stunPool.length) { push(`no stunnable ${eff.tag ? '[' + eff.tag + ']' : stunFilt} to stun`); break; }
        // The ACTING player's plan drives both tag order and target_preference,
        // even when the pool is the opponent's board -- destroy_target and every
        // other targeted effect read `plan`/`tpref` the same way. Reading the
        // target owner's plan instead would make stun the odd one out.
        const stunT  = selectTarget(filterByTagOrder(stunPool, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
        const stunN  = Math.max(0, Math.floor(a) || 0);
        const stunNm = cardDefs[stunT.card_id]?.name || stunT.card_id;
        const wasUp  = !stunT.utilized;
        stunT.utilized     = true;
        stunT.stunned      = true;
        stunT.stunCounters = (stunT.stunCounters || 0) + stunN;
        push(stunN > 0
          ? `stunned ${eff.side === 'own' ? 'your own ' : ''}${stunNm} for ${stunN} (${stunT.stunCounters} stun counters${wasUp ? '' : ', was already utilized'})`
          : `stunned ${eff.side === 'own' ? 'your own ' : ''}${stunNm}${wasUp ? '' : ' (was already utilized)'}`);
        break;
      }

      case 'mute_permanent': {
        // v4.22. Blanks a permanent: while muted it prints no passives and
        // fires no triggers, but keeps its melee/health/armor and every
        // counter already on it. Mute is not a rewind -- tokens already made
        // and counters already placed stay. Four things worth knowing:
        //
        // 1. `amount` is the mute DURATION in the owner's turns, and the
        //    permanent case is the separate boolean `until_leaves` -- NOT
        //    amount=0. See the note at the duration branch below for why a
        //    zero sentinel cannot work here.
        // 2. It does NOT tap. Stun taps and Mute does not; a muted creature
        //    still attacks and still contributes armor. They stack freely and
        //    a permanent can be both.
        // 3. It does NOT fire on_utilized / on_opponent_utilized, for the same
        //    reason stun does not: those mean "the owner spent this", and a
        //    mute is something done TO the permanent.
        // 4. Stacking is additive, per kkoechel, matching stun. Once
        //    mutePermanent is set no duration can take it back -- Mute 0 is
        //    strictly the strongest and a later Mute 2 must not shorten it.
        const muteTo   = eff.side === 'own' ? ap : op;
        const muteFilt = eff.target_supertype || 'permanent';
        const mutePool = muteTo.board.filter(e => {
          if (e.destroyed) return false;
          const d = cardDefs[e.card_id];
          // tag beats supertype, the same either/or rule stun uses.
          // entryHasTag, not d.tags: it also sees the synthetic 'token' tag.
          const m = eff.tag ? entryHasTag(e, eff.tag, cardDefs) : matchesSupertype(d, muteFilt);
          if (!m) return false;
          // Optional cost gate, same shape as stun's (v4.20). '' as the
          // supertype because the tag/supertype test above already decided
          // membership.
          if (eff.cost_op && !matchesCostFilter(d, '', eff.cost_op, eff.cost_threshold || 0)) return false;
          // isUnmutable reads RAW passives on purpose -- see its definition.
          // isUntargetable does not, so a permanent already muted has lost its
          // untargetable and can be re-muted; that is intended.
          return !isUntargetable(e, cardDefs) && !isUnmutable(e, cardDefs);
        });
        if (!mutePool.length) { push(`no mutable ${eff.tag ? '[' + eff.tag + ']' : muteFilt} to mute`); break; }
        // The ACTING player's plan drives tag order and target_preference even
        // when the pool is the opponent's board, exactly as stun does.
        const muteT   = selectTarget(filterByTagOrder(mutePool, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
        const muteNm  = cardDefs[muteT.card_id]?.name || muteT.card_id;
        const muteOwn = eff.side === 'own' ? 'your own ' : '';
        // v4.22. The "until it leaves play" case is its OWN BOOLEAN, not
        // amount=0. kkoechel asked for a missing duration to mean "no mute"
        // rather than the strongest mute (2026-10-12), and that distinction
        // is simply not expressible as a zero: Go and Python both unmarshal
        // an absent `amount` to exactly 0, so no engine but this one could
        // tell the two apart -- the stun arm says as much in its own comment,
        // and reshuffleSweep already chose a string over amount=0 for this
        // very reason. So: until_leaves:true is permanent, any amount N>0 is
        // N turns, and everything else (absent, 0, null, a computed 0) is no
        // mute at all. Unambiguous in all four engines.
        const mutePerm = eff.until_leaves === true;
        if (mutePerm) {
          muteT.mutePermanent = true;
          push(`muted ${muteOwn}${muteNm} until it leaves play`);
        } else {
          const muteN = Math.max(0, Math.floor(a) || 0);
          if (muteN === 0) { push(`no mute applied to ${muteOwn}${muteNm} (no duration set)`); break; }
          muteT.muted        = true;
          muteT.muteCounters = (muteT.muteCounters || 0) + muteN;
          push(muteT.mutePermanent
            ? `muted ${muteOwn}${muteNm} for ${muteN} (already muted until it leaves play)`
            : `muted ${muteOwn}${muteNm} for ${muteN} (${muteT.muteCounters} mute counters)`);
        }
        break;
      }

      case 'utilize_own_permanent': {
        const target = eff.target_supertype ?? 'permanent';
        const count  = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let utilized = 0;
        while (utilized < count) {
          // Recomputed fresh each iteration, not filtered from a pre-loop
          // snapshot -- on_utilized/on_opponent_utilized can fire arbitrary
          // passive effects (destroy, reanimate, create_token_copy, ...)
          // that mutate ap.board, so a stale pool could keep offering an
          // entry no longer actually on the board. See validate.py's
          // matching fix for the full explanation (Go had the same bug as
          // an outright crash; found via the combinatorial fake-card
          // regression sweep).
          // v4.28: only_tags/exclude_tags, the same pair sacrifice has carried
          // since v3.83 -- requested for utilize 2026-10-15. exclude beats
          // only, matching every other site that reads them.
          // v4.50 self -- see utilize_and. Both arms read it, because the Go
          // engines share one candidate helper between them and an engine that
          // honoured it in only one arm would drift the moment a card used it.
          const pool = ap.board.filter(e => !e.utilized && !(eff.self && (!source || e.iid !== source.iid))
            && matchesSupertype(cardDefs[e.card_id], target)
            && !entryExcluded(e, cardDefs, eff.exclude_tags)
            && !entryLacksRequired(e, cardDefs, eff.only_tags));
          if (!pool.length) break;
          const t = selectUtilizeTarget(pool, cpref, rand, cardDefs);
          t.utilized = true;
          push(`utilized ${cardDefs[t.card_id]?.name || t.card_id}`);
          firePassives(ap, 'on_utilized', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan, t);
          firePassives(ap, 'on_self_utilized', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan, t);
          firePassives(op, 'on_opponent_utilized', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan, t);
          utilized++;
        }
        if (!utilized) push(`no unutilized ${target} to utilize`);
        break;
      }

      case 'utilize_and': {
        const target = eff.target_supertype ?? 'permanent';
        const count  = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        // v4.50 self: utilize THIS card as the cost. The twin of
        // sacrifice_and's `self` (v4.21) and written with the same
        // `e.iid !== source.iid` idiom, so the two costs read alike. The
        // ordinary filters still apply on top, exactly as they do there: a
        // self-utilize whose own card fails target_supertype or a tag filter
        // pays nothing and the `then` never fires.
        //
        // No per-turn rule is needed, unlike SELF_SAC_PER_TURN. Nothing in any
        // engine ever sets utilized back to false outside the turn-start
        // refresh, so a card can self-utilize at most once per untap: the tap
        // IS the cap, and it costs the card its attack for the turn.
        const selfU = !!eff.self;
        // v3.88: all-or-nothing -- see validate.go's utilize_and. Not enough
        // candidates to pay in full means nothing is consumed at all.
        const availU = ap.board.filter(e => !e.utilized && matchesSupertype(cardDefs[e.card_id], target)
          && !entryExcluded(e, cardDefs, eff.exclude_tags)
          && !entryLacksRequired(e, cardDefs, eff.only_tags)
          && !(selfU && (!source || e.iid !== source.iid)));
        const canPayU = availU.length >= count;
        let utilized = 0;
        while (canPayU && utilized < count) {
          // See utilize_own_permanent above for why this is recomputed
          // fresh each iteration rather than filtered from a snapshot, and
          // why the v4.28 tag filters are applied here too.
          const pool = ap.board.filter(e => !e.utilized && matchesSupertype(cardDefs[e.card_id], target)
            && !entryExcluded(e, cardDefs, eff.exclude_tags)
            && !entryLacksRequired(e, cardDefs, eff.only_tags)
            && !(selfU && (!source || e.iid !== source.iid)));
          if (!pool.length) break;
          const t = selectUtilizeTarget(pool, cpref, rand, cardDefs);
          t.utilized = true;
          push(`utilized ${cardDefs[t.card_id]?.name || t.card_id}${selfU ? ' (itself)' : ''}`);
          firePassives(ap, 'on_utilized', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan, t);
          firePassives(ap, 'on_self_utilized', ap, op, rand, cardDefs, log, fromPassive, plan, opPlan, t);
          firePassives(op, 'on_opponent_utilized', op, ap, rand, cardDefs, log, fromPassive, opPlan, plan, t);
          utilized++;
        }
        if (utilized !== count) {
          push(selfU ? `this card is already utilized — effect skipped`
                     : `fewer than ${count} unutilized ${target} — effect skipped`);
          break;
        }
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'sacrifice_own_permanent': {
        const target = eff.target_supertype ?? 'permanent';
        const count  = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let sacrificed = 0;
        while (sacrificed < count) {
          // Recomputed fresh each iteration, not filtered from a pool built
          // once up front -- matches utilize_own_permanent's pattern and
          // validate.go's sacrificeCands() call. on_death/on_leave can fire
          // arbitrary effects (reanimate_tagged, create_token_copy, ...) that
          // mutate ap.board; a stale pool would never offer a just-reanimated
          // entry to a LATER iteration of this same multi-count sacrifice,
          // silently diverging from Go (which does). The fresh `!e.destroyed`
          // filter also covers what used to need a separate post-pick
          // recheck: an in-flight mass-destroy cascade's on_death chain
          // claiming an entry still physically on the board (same protocol
          // as destroy_tagged/destroy_all/destroy_permanents_by_cost, v3.58).
          const pool = ap.board.filter(e => !e.destroyed && matchesSupertype(cardDefs[e.card_id], target)
            && !(eff.exclude_self && source && e.iid === source.iid)
            && !entryExcluded(e, cardDefs, eff.exclude_tags)
            && !entryLacksRequired(e, cardDefs, eff.only_tags));
          if (!pool.length) break;
          const t = selectTarget(pool, cpref, rand, cardDefs);
          t.destroyed = true;
          push(`sacrificed ${cardDefs[t.card_id]?.name || t.card_id}`);
          fireTriggerOn(t, 'on_death', ap, op, rand, cardDefs, log, plan, opPlan);
          fireTriggerOn(t, 'on_leave', ap, op, rand, cardDefs, log, plan, opPlan);
          ap.board = ap.board.filter(c => c !== t);
          if (!championLeave(ap, t.card_id, log, cardDefs)) toDiscardOrExile(ap, t, cardDefs, rand, log, op, plan, opPlan);
          sacrificed++;
        }
        if (!sacrificed) push(`no ${target} to sacrifice`);
        break;
      }

      case 'sacrifice_and': {
        const target = eff.target_supertype ?? 'permanent';
        const count  = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        // v4.43 the self-sacrifice rule -- see SELF_SAC_PER_TURN. Checked
        // BEFORE a candidate is picked and skipping the whole case, which is
        // what keeps the `then` from firing: a Book that is not sacrificed must
        // not draw. Gated on `used >= cap` rather than `used + count > cap` so
        // a count > 1 self-sacrifice still falls through to the all-or-nothing
        // path below and reports itself the way it always has.
        // v4.65: `self_sac_per_turn` on the effect itself caps THIS card id
        // (all copies share one budget per player per turn) and wins over the
        // tag rule -- Ryan's per-card limit for Gem, Iron Coin, Wood Plank,
        // Scroll and Seed. The third element is the log label.
        const ssCardCap = Math.floor(Number(eff.self_sac_per_turn) || 0);
        const ssRule = (eff.self && source)
          ? (ssCardCap > 0 ? ['#' + source.card_id, ssCardCap, cardDefs[source.card_id]?.name || String(source.card_id)]
                           : selfSacRule(cardDefs, source.card_id))
          : null;
        const ssKey  = ssRule ? 'ssx:' + ssRule[0] : null;
        if (ssRule) {
          ap.mptShared = ap.mptShared || {};
          const used = ap.mptShared[ssKey] || 0;
          if (used >= ssRule[1]) {
            // Counted PAST the cap, purely so the line below prints ONCE per
            // player per turn per tag. Every held-back copy logging for itself
            // measured 3,139 lines a match against 467 before, on a board that
            // reached 192 books -- and a realistic four-relic board gets to the
            // same shape, just slower. Counting past the cap can never steal
            // budget, because it only ever grows once already blocked.
            ap.mptShared[ssKey] = used + 1;
            if (used === ssRule[1]) {
              push(`${cardDefs[source.card_id]?.name || source.card_id} self-sacrifice capped for this turn (${ssRule[1]} ${ssRule[2] ?? ssRule[0]} a turn)`);
            }
            break;
          }
        }
        // v3.88: all-or-nothing -- see validate.go's utilize_and. Not enough
        // candidates to pay in full means nothing is consumed at all.
        // v4.21 `self`: the exact mirror of exclude_self above -- restrict the
        // pool to the entry that FIRED this, so a card can say "Sacrifice
        // this" and mean it. The token cards expressed that through a tag
        // filter matching only themselves, which is not the same thing: Gold
        // Coin's filter also matches Treasure Horde, and a Bee Egg could hatch
        // by eating a Dragon Egg (measured 2.9% of egg sacrifices).
        //
        // The other filters still apply on top, so self + a tag the card does
        // not carry pays nothing, which is the honest reading. count > 1 can
        // never be met either, and all-or-nothing then consumes nothing at
        // all. A null source (fired from somewhere with no board entry) pays
        // nothing rather than falling back to "any".
        const availS = ap.board.filter(e => !e.destroyed && matchesSupertype(cardDefs[e.card_id], target)
          && !(eff.exclude_self && source && e.iid === source.iid)
          && !(eff.self && (!source || e.iid !== source.iid))
          && !entryExcluded(e, cardDefs, eff.exclude_tags)
          && !entryLacksRequired(e, cardDefs, eff.only_tags));
        const canPayS = availS.length >= count;
        let sacrificed = 0;
        while (canPayS && sacrificed < count) {
          // See sacrifice_own_permanent above for why this is recomputed
          // fresh each iteration rather than filtered from a pool built once.
          const pool = ap.board.filter(e => !e.destroyed && matchesSupertype(cardDefs[e.card_id], target)
            && !(eff.exclude_self && source && e.iid === source.iid)
            && !(eff.self && (!source || e.iid !== source.iid))
            && !entryExcluded(e, cardDefs, eff.exclude_tags)
            && !entryLacksRequired(e, cardDefs, eff.only_tags));
          if (!pool.length) break;
          const t = selectTarget(pool, cpref, rand, cardDefs);
          t.destroyed = true;
          push(`sacrificed ${cardDefs[t.card_id]?.name || t.card_id}`);
          fireTriggerOn(t, 'on_death', ap, op, rand, cardDefs, log, plan, opPlan);
          fireTriggerOn(t, 'on_leave', ap, op, rand, cardDefs, log, plan, opPlan);
          ap.board = ap.board.filter(c => c !== t);
          if (!championLeave(ap, t.card_id, log, cardDefs)) toDiscardOrExile(ap, t, cardDefs, rand, log, op, plan, opPlan);
          sacrificed++;
        }
        if (sacrificed !== count) { push(`fewer than ${count} ${target} to sacrifice — effect skipped`); break; }
        // Charged only on a sacrifice that actually happened.
        if (ssRule) ap.mptShared[ssKey] = (ap.mptShared[ssKey] || 0) + sacrificed;
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      // Repeatable trigger-effect twins of every additional_cost type (utilize/
      // sacrifice already had these above) -- lets an ability say "each turn,
      // pay Honor/Life/Shields/discard/recycle/exile from discard -> then
      // effect", not just as a one-time play cost. Same "attempt payment, only
      // fire `then` if it actually succeeded" shape as utilize_and/sacrifice_and.
      case 'pay_honor_and': {
        const cost = Math.max(0, Math.floor(a) || 0);
        if (ap.honor < cost) { push(`not enough honor to pay ${cost} — effect skipped`); break; }
        ap.honor -= cost; tally(ap, 'honor_lost', cost);
        push(`paid ${cost} honor → ${ap.honor} honor`);
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'pay_life_and': {
        const cost = Math.max(0, Math.floor(a) || 0);
        if (ap.life < cost) { push(`not enough life to pay ${cost} — effect skipped`); break; }
        ap.life -= cost; tally(ap, 'life_lost', cost);
        if (cost > 0) ap.lifeLossCause = 'self';
        push(`paid ${cost} life → ${ap.life} life`);
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'pay_shields_and': {
        const cost = Math.max(0, Math.floor(a) || 0);
        if (ap.shields < cost) { push(`not enough shields to pay ${cost} — effect skipped`); break; }
        ap.shields -= cost;
        push(`paid ${cost} shields → ${ap.shields} shields`);
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'discard_and': {
        // v3.92: a literal 0 pays the WHOLE zone as the cost (the sentinel the
        // non-cost siblings already use). The length gate then always passes,
        // so the `then` effects fire even on an empty zone. Mirrors validate.go.
        const n = literalZeroMeansAll(eff) ? ap.hand.length : Math.min(10, Math.max(1, Math.floor(a) || 1));
        if (ap.hand.length < n) { push(`not enough cards in hand to discard ${n} — effect skipped`); break; }
        for (let i = 0; i < n; i++) {
          const idx = pickDiscardFrom(ap.hand.map((_, ci) => ci), ap.hand, cardDefs, plan, rand);
          if (idx < 0) break;
          arriveInDiscard(ap, ap.hand.splice(idx, 1)[0], cardDefs, rand, log, 'hand', op, plan, opPlan);
        }
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'recycle_and': {
        // v3.92: a literal 0 pays the WHOLE zone as the cost (the sentinel the
        // non-cost siblings already use). The length gate then always passes,
        // so the `then` effects fire even on an empty zone. Mirrors validate.go.
        const n = literalZeroMeansAll(eff) ? ap.discard.length : Math.min(10, Math.max(1, Math.floor(a) || 1));
        if (ap.discard.length < n) { push(`not enough cards in discard to recycle ${n} — effect skipped`); break; }
        let pool = ap.discard.slice();
        const picked = [];
        for (let i = 0; i < n; i++) {
          const cid = selectPileCard(pool, cpref, cardDefs, rand);
          pool.splice(pool.indexOf(cid), 1);
          picked.push(cid);
        }
        ap.discard = pool;
        ap.deck = shuffle([...ap.deck, ...picked], rand);
        tally(ap, 'cards_recycled', n);
        push(`recycled ${n} card${n !== 1 ? 's' : ''} from discard into library`);
        // Before the riders, matching utilize_and: the cost event resolves and
        // its watchers react, then the `then` effects run.
        fireRecycle(ap, op, rand, cardDefs, log, fromPassive, plan, opPlan);
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'exile_discard_and': {
        // v3.92: a literal 0 pays the WHOLE zone as the cost (the sentinel the
        // non-cost siblings already use). The length gate then always passes,
        // so the `then` effects fire even on an empty zone. Mirrors validate.go.
        const n = literalZeroMeansAll(eff) ? ap.discard.length : Math.min(10, Math.max(1, Math.floor(a) || 1));
        if (ap.discard.length < n) { push(`not enough cards in discard to exile ${n} — effect skipped`); break; }
        let pool = ap.discard.slice();
        for (let i = 0; i < n; i++) {
          const cid = selectPileCard(pool, cpref, cardDefs, rand);
          pool.splice(pool.indexOf(cid), 1);
        }
        ap.discard = pool;
        tally(ap, 'cards_exiled', n);
        push(`exiled ${n} card${n !== 1 ? 's' : ''} from discard`);
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      // v3.99 stat counters -- see shell/validate.go for the full note.
      // Permanent counterpart to anthems; creatures only; signed amount covers
      // both directions. adjust_health is NOT a counter: hp has no ceiling and
      // no per-turn reset, so positive heals and negative pierces, bypassing
      // both curArmor and indestructible.
      case 'add_melee_counters':
      case 'add_armor_counters':
      case 'adjust_health': {
        // v4.54 `self`: THIS card, the entry that fired it -- "a supplement
        // that gives this creature +2 melee" (Ryan, 2026-10-31). It names one
        // card, so the tag/type/cost filters do not apply; it still needs
        // health, like every other target here. On the board BY IDENTITY: an
        // entry pruned without `destroyed` set must not be buffed.
        if (eff.self) {
          // Same liveness test as a Saga's (destroyed, dead, or off the
          // board), plus it must have health at all.
          const t = source && !source.destroyed && source.hp !== null && source.hp > 0 && ap.board.includes(source) ? source : null;
          if (!t) { push(`no card to adjust`); break; }
          if (eff.type === 'add_melee_counters') {
            t.meleeCounters = (t.meleeCounters || 0) + a;
          } else if (eff.type === 'add_armor_counters') {
            t.armorCounters = (t.armorCounters || 0) + a;
            t.curArmor = Math.max(0, (t.curArmor || 0) + a);
          } else {
            t.hp += a;
          }
          push(`${cardDefs[t.card_id]?.name || t.card_id} adjusted`);
          break;
        }
        const toOpp = eff.side === 'opponent';
        const side  = toOpp ? op : ap;
        const pref  = toOpp ? tpref : spref;
        const pool = counterStatPool(eff, side, a, cardDefs);
        if (!pool.length) break;
        let targets = pool;
        if (eff.scope !== 'all') {
          const t = selectTarget(filterByTagOrder(pool, plan?.target_tag_order, cardDefs), pref, rand, cardDefs);
          if (!t) break;
          targets = [t];
        }
        for (const c of targets) {
          if (eff.type === 'add_melee_counters') {
            c.meleeCounters = (c.meleeCounters || 0) + a;
          } else if (eff.type === 'add_armor_counters') {
            c.armorCounters = (c.armorCounters || 0) + a;
            c.curArmor = Math.max(0, (c.curArmor || 0) + a);
          } else {
            c.hp += a;
          }
        }
        push(`${targets.length} creature(s) adjusted`);
        break;
      }

      case 'deal_damage_to_tagged': {
        const tag = eff.tag || '';
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        let hit = 0;
        // Every damageable entry, INCLUDING one already at 0 hp or below from
        // an earlier effect this resolution -- as validate.go / validate.py /
        // engine.go always did. JS alone skipped it (c.hp > 0), so a creature
        // a first sweep left at exactly 0 took no more damage from a second
        // sweep, and a heal later in the same cascade (Plantaxian Sprout's
        // on_leave +1) lifted it back to 1 hp and it LIVED -- in JS only.
        // Found by catalog_parity.py (deck p1d19: Fire Goat's two sweeps on
        // Ritualist of Ki-Zor), 2026-11-04. Ryan: "the creature should die".
        for (const c of op.board) {
          if (c.hp !== null && entryHasTag(c, tag, cardDefs)) {
            hit++;
            if (isIndestructible(c, cardDefs)) { push(`${cardDefs[c.card_id]?.name || c.card_id} [${tag}] is indestructible — no damage`); continue; }
            const abs = dmgCreature(c, amp);
            push(`dealt ${amp} to ${cardDefs[c.card_id]?.name || c.card_id} [${tag}]${abs > 0 ? ` (${abs} absorbed)` : ''} → ${c.hp} hp${amp > a ? ' (amplified)' : ''}`);
          }
        }
        if (!hit) push(`no damageable tagged "${tag}" permanents`);
        if (eff.symmetric) {
          let hitOwn = 0;
          for (const c of ap.board) {
            if (c.hp !== null && entryHasTag(c, tag, cardDefs)) {   // same rule, own side
              hitOwn++;
              if (isIndestructible(c, cardDefs)) { push(`your ${cardDefs[c.card_id]?.name || c.card_id} [${tag}] is indestructible — no damage`); continue; }
              const abs = dmgCreature(c, amp);
              push(`dealt ${amp} to your ${cardDefs[c.card_id]?.name || c.card_id} [${tag}]${abs > 0 ? ` (${abs} absorbed)` : ''} → ${c.hp} hp${amp > a ? ' (amplified)' : ''}`);
            }
          }
          if (!hitOwn) push(`no damageable tagged "${tag}" permanents on your side`);
        }
        break;
      }

      case 'destroy_tagged': {
        const tag = eff.tag || '';
        // .destroyed is checked live (not just collected into a local
        // toDestroy snapshot) so a nested recursive call -- triggered by
        // fireLeaveTrigger below, before op.board gets reassigned at the
        // end of this pass -- sees it too and skips re-processing an entry
        // another still-open frame already destroyed. Without this, two
        // mutually-destructive on-death cards on opposing sides could each
        // keep rediscovering and re-killing the other, bounded only by
        // MAX_EFFECT_DEPTH -- and the exact bound reached could differ
        // from Go/Python (confirmed live: two Death Arbiters, 2026-08-03).
        const matched = op.board.filter(c => !c.destroyed && entryHasTag(c, tag, cardDefs));
        if (!matched.length) { push(`no tagged "${tag}" permanents to destroy`); }
        else {
          const blocked = [];
          // Fire on_leave (and its on_tag_death tag-watcher fan-out) for every
          // entry BEFORE mutating op.board -- a watcher's own source card may
          // be in this same destroyed batch, and it needs to still be found on
          // the board when the watched entries' triggers fire (matches Go/Python).
          for (const t of matched) {
            // Re-check .destroyed live, per-iteration -- toDestroy was only
            // filtered ONCE up front, so an earlier iteration's own nested
            // cascade (via fireLeaveTrigger below) can destroy a LATER entry
            // still sitting in this same pre-computed array. Go/Python check
            // this fresh on every loop iteration (they re-scan the board
            // directly rather than pre-filtering into a snapshot array);
            // without this, JS re-fired a leave trigger a second time for an
            // entry a nested cascade already killed (confirmed live: extra
            // destroy_target firings off an already-dead Death Arbiter
            // victim, 2026-08-03).
            if (t.destroyed) continue;
            // v4.43: isIndestructible is checked LIVE here, per iteration,
            // instead of pre-partitioning the batch before the loop. A granter
            // of the keyword can be IN this same batch -- Royal Honey Guard
            // (845) gives indestructible to your royal creatures and is itself
            // a creature, so a Wrath of Wog that kills it before reaching The
            // Silent Queen (879, royal) must leave her destructible. Go and
            // Python always read it live; JS pre-partitioned, kept her alive,
            // and desynced 6 of 14,742 catalogue-sweep matches. Collected and
            // printed after the loop so the log keeps its existing order.
            if (isIndestructible(t, cardDefs)) { blocked.push(t); continue; }
            t.destroyed = true;
            push(`destroyed ${cardDefs[t.card_id]?.name || t.card_id} [${tag}]`);
            fireLeaveTrigger(t, op, ap, rand, cardDefs, log, undefined, opPlan, plan);
            if (!championLeave(op, t.card_id, log, cardDefs)) toDiscardOrExile(op, t, cardDefs, rand, log, ap, opPlan, plan);
          }
          for (const t of blocked) push(`${cardDefs[t.card_id]?.name || t.card_id} [${tag}] is indestructible — not destroyed`);
        }
        // Re-filter unconditionally, even if THIS call matched nothing of its
        // own -- Go (`op.board = removeDestroyed(op.board)`) and Python
        // (`op['board'] = [c for c in op['board'] if not c.get('destroyed')]`)
        // both run their equivalent re-filter every time, which incidentally
        // flushes out any OTHER already-.destroyed-but-unpruned entry left
        // dangling by an outer, still-in-progress destroy cascade (e.g. a
        // watcher-triggered destroy_tagged call that finds zero matches of
        // its own). Gating this behind "did THIS call's own matched list find
        // anything" let such a stale entry linger on op.board in JS alone --
        // confirmed live via a real card (Fire Djinn, id 28)'s on_death
        // count_source counting one extra still-dangling entry, 2026-08-04.
        op.board = op.board.filter(c => !c.destroyed);
        if (eff.symmetric) {
          const matchedOwn = ap.board.filter(c => !c.destroyed && entryHasTag(c, tag, cardDefs));
          if (!matchedOwn.length) { push(`no tagged "${tag}" permanents on your side to destroy`); }
          else {
            const blockedOwn = [];
            for (const t of matchedOwn) {
              if (t.destroyed) continue;
              // v4.43 live indestructible check -- see the opponent side above.
              if (isIndestructible(t, cardDefs)) { blockedOwn.push(t); continue; }
              t.destroyed = true;
              push(`destroyed your ${cardDefs[t.card_id]?.name || t.card_id} [${tag}]`);
              fireLeaveTrigger(t, ap, op, rand, cardDefs, log, undefined, plan, opPlan);
              if (!championLeave(ap, t.card_id, log, cardDefs)) toDiscardOrExile(ap, t, cardDefs, rand, log, op, plan, opPlan);
            }
            for (const t of blockedOwn) push(`your ${cardDefs[t.card_id]?.name || t.card_id} [${tag}] is indestructible — not destroyed`);
          }
          // See the unconditional re-filter comment above.
          ap.board = ap.board.filter(c => !c.destroyed);
        }
        break;
      }

      // Exile is strictly stronger removal than Destroy: it bypasses
      // indestructible (deliberate design choice, confirmed with kkoechel --
      // this is what makes Exile meaningfully distinct from a Destroy variant
      // rather than just a reskin), and the card never enters ANY zone --
      // not op.discard, nowhere -- so Reanimate/Recycle/etc. can never bring
      // it back. Amount-capped random selection (not "all matching" like
      // destroy_tagged), so it checks isUntargetable the same way
      // destroy_random_tagged's random-pick does.
      case 'exile_tagged': {
        const tag = eff.tag || '';
        // v3.94: a literal 0 exiles EVERY match (Void Eclipse). Sized past both
        // boards so neither slice truncates. destroy_tagged needs no sentinel --
        // it reads no amount and already hits every match. See validate.go.
        const amt = literalZeroMeansAll(eff) ? (op.board.length + ap.board.length) : Math.max(1, a);
        // .destroyed excluded here too (see destroy_target above for why).
        // v4.09: an OPTIONAL cost gate, same shape as the single-target
          // destroys and the stat counters. `eff.cost_op` absent means NO
          // filter -- never "match nothing" -- which is the neutral default
          // an optional editor field has to have, and the inversion that made
          // four cards silently inert in v4.00/v4.01.
        const costOK = c => !eff.cost_op || matchesCostFilter(cardDefs[c.card_id], '', eff.cost_op, eff.cost_threshold ?? 0);
        const matched = op.board.filter(c => !c.destroyed && !isUntargetable(c, cardDefs) && entryHasTag(c, tag, cardDefs) && costOK(c));
        if (!matched.length) { push(`no tagged "${tag}" permanents to exile`); }
        else {
          const toExile = shuffle(matched, rand).slice(0, amt);
          for (const t of toExile) t.destroyed = true;
          // Fire on_leave (and its on_tag_death tag-watcher fan-out) for every
          // entry BEFORE mutating op.board -- a watcher's own source card may
          // be in this same exiled batch, and it needs to still be found on
          // the board when the exiled entries' triggers fire (matches Go/Python).
          for (const t of toExile) {
            tally(op, 'cards_exiled', 1);
            push(`exiled ${cardDefs[t.card_id]?.name || t.card_id} [${tag}]`);
            fireLeaveTrigger(t, op, ap, rand, cardDefs, log, undefined, opPlan, plan);
            championLeave(op, t.card_id, log, cardDefs); // champion redirects to its zone; anyone else just vanishes
          }
          op.board = op.board.filter(c => !toExile.includes(c));
        }
        if (eff.symmetric) {
          const matchedOwn = ap.board.filter(c => !c.destroyed && !isUntargetable(c, cardDefs) && entryHasTag(c, tag, cardDefs) && costOK(c));
          if (!matchedOwn.length) { push(`no tagged "${tag}" permanents on your side to exile`); }
          else {
            const toExileOwn = shuffle(matchedOwn, rand).slice(0, amt);
            for (const t of toExileOwn) t.destroyed = true;
            for (const t of toExileOwn) {
              tally(ap, 'cards_exiled', 1);
              push(`exiled your ${cardDefs[t.card_id]?.name || t.card_id} [${tag}]`);
              fireLeaveTrigger(t, ap, op, rand, cardDefs, log, undefined, plan, opPlan);
              championLeave(ap, t.card_id, log, cardDefs);
            }
            ap.board = ap.board.filter(c => !toExileOwn.includes(c));
          }
        }
        break;
      }

      case 'deal_damage_to_random_tagged': {
        const tag  = eff.tag || '';
        const pool = op.board.filter(c => !c.destroyed && c.hp !== null && c.hp > 0 && !isUntargetable(c, cardDefs) && entryHasTag(c, tag, cardDefs));
        if (!pool.length) { push(`no damageable tagged "${tag}" permanents`); break; }
        const t = eff.target_highest_cost ? selectHighestCostTarget(pool, cardDefs, rand) : selectTarget(filterByTagOrder(pool, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
        if (isIndestructible(t, cardDefs)) { push(`${cardDefs[t.card_id]?.name || t.card_id} [${tag}] is indestructible — no damage`); break; }
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        const abs = dmgCreature(t, amp);
        push(`dealt ${amp} to ${cardDefs[t.card_id]?.name || t.card_id} [${tag}]${abs > 0 ? ` (${abs} absorbed)` : ''} → ${t.hp} hp${amp > a ? ' (amplified)' : ''}`);
        break;
      }

      case 'destroy_random_tagged': {
        const tag  = eff.tag || '';
        // .destroyed excluded here too (see destroy_target above for why).
        // tag === '' means NO tag constraint. An unguarded includes() is false
        // for every card, so the pool came back empty and Permanent
        // Impermanence (939) whiffed, logging `no tagged "" permanents to
        // destroy` four times against a full board. The supertype its triggers
        // DO set is applied by narrowByTypeAndCost. See validate.go.
        const pool = narrowByTypeAndCost(op.board.filter(c => !c.destroyed && !isUntargetable(c, cardDefs) && (!tag || entryHasTag(c, tag, cardDefs))), eff, cardDefs);
        if (!pool.length) { push(tag ? `no tagged "${tag}" permanents to destroy` : `no matching permanents to destroy`); break; }
        const t     = eff.target_highest_cost ? selectHighestCostTarget(pool, cardDefs, rand) : selectTarget(filterByTagOrder(pool, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
        const tName = cardDefs[t.card_id]?.name || t.card_id;
        if (isIndestructible(t, cardDefs)) { push(`${tName}${tag ? ` [${tag}]` : ''} is indestructible — not destroyed`); break; }
        t.destroyed = true;
        push(`destroyed ${tName}${tag ? ` [${tag}]` : ''}`);
        fireLeaveTrigger(t, op, ap, rand, cardDefs, log, undefined, opPlan, plan);
        op.board = op.board.filter(c => c !== t);
        if (!championLeave(op, t.card_id, log, cardDefs)) toDiscardOrExile(op, t, cardDefs, rand, log, ap, opPlan, plan);
        break;
      }

      case 'reanimate_random_tagged': {
        const tag = eff.tag || '';
        const pool = reanimateCandidates(ap, cardDefs, tag, '', eff.cost_op, eff.cost_threshold ?? 0);
        if (!pool.length) { push(`no tagged "${tag}" permanents in your discard to reanimate`); break; }
        const cid = selectPileCard(pool, cpref, cardDefs, rand);
        const idx = ap.discard.indexOf(cid);
        if (idx !== -1) ap.discard.splice(idx, 1);
        reanimateOne(ap, op, cid, cardDefs, rand, log, undefined, undefined, plan, opPlan);
        break;
      }

      case 'reanimate_tagged': {
        const tag = eff.tag || '';
        const pool = reanimateCandidates(ap, cardDefs, tag, '', eff.cost_op, eff.cost_threshold ?? 0);
        if (!pool.length) { push(`no tagged "${tag}" permanents in your discard to reanimate`); break; }
        for (const cid of pool) {
          const idx = ap.discard.indexOf(cid);
          if (idx !== -1) ap.discard.splice(idx, 1);
          reanimateOne(ap, op, cid, cardDefs, rand, log, undefined, undefined, plan, opPlan);
        }
        break;
      }

      // v3.97: discard -> HAND by tag, the counterpart to reanimate_tagged
      // above. Deliberately NOT permanent-only: reanimateCandidates filters
      // to permanents because it puts things on a board, but buying a SPELL
      // back is the main reason to recur to hand. amount=0 = every match,
      // gated on literalZeroMeansAll. No hand cap: the turn loop discards
      // down to maxHand at end of turn, same as tutor-to-hand.
      case 'return_tagged_to_hand': {
        const tag = eff.tag || '';
        const all = literalZeroMeansAll(eff);
        const want = a;
        let moved = 0;
        for (const cid of ap.discard.slice()) {
          if (!all && moved >= want) break;
          const def = cardDefs[cid];
          if (!def) continue;
          if (cid === ap.championId) continue;
          if (tag && !(def.tags ?? []).includes(tag)) continue;
          if (hasExcludedTag(cardDefs, cid, eff.exclude_tags) || lacksRequiredTag(cardDefs, cid, eff.only_tags)) continue;
          if (eff.cost_op && !matchesCostFilter(def, '', eff.cost_op, eff.cost_threshold ?? 0)) continue;
          const idx = ap.discard.indexOf(cid);
          if (idx !== -1) ap.discard.splice(idx, 1);
          ap.hand.push(cid);
          moved++;
        }
        push(moved ? `returned ${moved} card(s) from discard to hand` : 'no matching card(s) in discard to return');
        break;
      }

      // Search the LIBRARY (unlike Reanimate, which searches the discard
      // pile) for either a specific named card (search_mode:'name', up to
      // `amount` copies of that exact card_id) or a random selection of
      // cards matching a tag (search_mode:'tag', up to `amount` distinct
      // matches, chosen randomly since the library is a much bigger pool
      // than the discard pile reanimate draws from). Found cards go to
      // hand or straight into play depending on `destination` -- for
      // 'play', tag-mode candidates are pre-filtered to permanents only
      // (so a designer's tag choice can't silently under-deliver), while
      // name-mode defensively discards (rather than crashes on) a found
      // card that isn't a permanent -- no real card should ever be
      // designed this way, but the engine shouldn't do something
      // undefined if one is. The remaining library is reshuffled
      // afterward so repeated searches can't leak deck order.
      case 'tutor': {
        const mode = eff.search_mode || 'tag';
        const dest = eff.destination || 'play';
        const amt  = Math.max(1, a);
        let cids = [];
        if (mode === 'name') {
          // Number-coerced comparison, not strict `===` -- some callers
          // (e.g. game/deck-gallery.php's Challenge/vs-Strawmen simulator)
          // represent a deck's card_ids as strings rather than numbers,
          // which every OTHER card_id comparison in this engine tolerates
          // transparently because object-property lookups (cardDefs[cid])
          // auto-coerce keys to strings either way. A direct value
          // comparison doesn't get that same free pass, so without this,
          // "1" (deck) !== 1 (effect's card_id) silently matched nothing --
          // reported by Tristal: their name-mode Tutor card never found any
          // copies of its target in that simulator, even with dozens still
          // in the library.
          const wantId = +eff.card_id;
          for (const cid of ap.deck) {
            if (cids.length >= amt) break;
            if (+cid === wantId) cids.push(cid);
          }
        } else {
          const tag = eff.tag || '';
          let pool = ap.deck.filter(cid => {
            const def = cardDefs[cid];
            if (!def || !(def.tags ?? []).includes(tag)) return false;
            // v3.96: same only_tags/exclude_tags filters recycle_self and
            // sacrifice_and have had since v3.83. Both AND with the base tag
            // above -- lacksRequiredTag is OR within itself, so only_tags alone
            // could not mean "common AND relic". exclude_tags beats only_tags,
            // as everywhere else.
            if (hasExcludedTag(cardDefs, cid, eff.exclude_tags) || lacksRequiredTag(cardDefs, cid, eff.only_tags)) return false;
            // v4.03: and the same cost filter reanimate_tagged has had since
            // v3.87, so a search can say "a [relic] costing 3 or less" instead
            // of only ever "a [relic]". '' as the supertype is deliberate --
            // the dest==='play' perm check below is the tutor's only type
            // constraint, and a non-empty supertype here would be a KILL
            // SWITCH (see matchesCostFilter's note on cards 961/956).
            if (eff.cost_op && !matchesCostFilter(def, '', eff.cost_op, eff.cost_threshold ?? 0)) return false;
            if (dest === 'play' && !isPermanent(def)) return false;
            return true;
          });
          // Picked one at a time (same shape as utilize/sacrifice's own
          // pick-and-remove loops) so battle_plan.cost_preference can steer
          // which matches get fetched first -- previously always a pure
          // shuffle-and-slice with no way to prefer cheapest/costliest.
          while (cids.length < amt && pool.length) {
            const cid = selectPileCard(pool, cpref, cardDefs, rand);
            pool.splice(pool.indexOf(cid), 1);
            cids.push(cid);
          }
        }
        if (!cids.length) { push(`no matching card(s) found in library to tutor`); break; }
        for (const cid of cids) {
          const idx = ap.deck.indexOf(cid);
          if (idx !== -1) ap.deck.splice(idx, 1);
        }
        ap.deck = shuffle(ap.deck, rand);
        let landed = 0;
        for (const cid of cids) {
          if (dest === 'hand') {
            ap.hand.push(cid);
            landed++;
          } else if (dest === 'discard') {
            // v4.08: fetch straight to the discard pile -- the setup half of
            // gravecast / reanimate / recursion, which all read from there.
            // No permanence check: a discard pile holds spells as happily as
            // permanents, exactly as the hand does. The pool filter above
            // already gates only on dest === 'play' for the same reason.
            arriveInDiscard(ap, cid, cardDefs, rand, log, 'tutor', op, plan, opPlan);
            landed++;
          } else {
            const def = cardDefs[cid];
            if (def && isPermanent(def)) {
              reanimateOne(ap, op, cid, cardDefs, rand, log, 'library', undefined, plan, opPlan);
              landed++;
            } else {
              arriveInDiscard(ap, cid, cardDefs, rand, log, 'tutor', op, plan, opPlan);
              push(`  ${def?.name || cid} found but isn't a permanent — discarded instead of entering play`);
            }
          }
        }
        push(`  tutored ${landed}/${cids.length} card(s) from library to ${dest}`);
        break;
      }

      case 'create_token_copy': {
        // Conjures a fresh copy of a named permanent directly into play --
        // no deck/discard/hand interaction at all, unlike Tutor. The token
        // exiles instead of going to discard/hand whenever it later leaves
        // play (see the isToken checks in toDiscardOrExile/toHandOrExile).
        const amt = Math.min(Math.max(1, a), MAX_TOKEN_COPIES_PER_FIRE);
        if (eff.random_from) {
          // v4.53: a random permanent from a library. The pool is taken ONCE,
          // before any copy enters, and each copy costs exactly one rand().
          const tFrom = eff.random_from === 'opponent' ? op : ap;
          const pool = randomFromPool(tFrom, eff, cardDefs, true);
          if (!pool.length) { push(`  no matching permanent in ${eff.random_from === 'opponent' ? "opponent's" : 'your'} library to copy`); break; }
          const opp = eff.side === 'opponent';
          const tTo = opp ? op : ap, tOpp = opp ? ap : op;
          const tPlan = opp ? opPlan : plan, tOppPlan = opp ? plan : opPlan;
          for (let i = 0; i < amt; i++) {
            const cid = pool[Math.floor(rand() * pool.length)];
            reanimateOne(tTo, tOpp, cid, cardDefs, rand, log, '', true, tPlan, tOppPlan, opp);
          }
          break;
        }
        const def = cardDefs[eff.card_id];
        if (def && isPermanent(def)) {
          // v3.97: side="opponent" conjures onto THEIR board instead of yours.
          // Swapping ap/op swaps plan/opPlan too, so the token's on_enter sees
          // the plan of whoever now owns it.
          const opp = eff.side === 'opponent';
          const tTo = opp ? op : ap, tOpp = opp ? ap : op;
          const tPlan = opp ? opPlan : plan, tOppPlan = opp ? plan : opPlan;
          for (let i = 0; i < amt; i++) reanimateOne(tTo, tOpp, eff.card_id, cardDefs, rand, log, '', true, tPlan, tOppPlan, opp);
        } else {
          push(`  no permanent found for card_id ${eff.card_id} to create a token copy of`);
        }
        break;
      }

      case 'chance': {
        // v4.37. One RNG draw, taken BEFORE either branch resolves, so a
        // branch's own effects can never shift the stream for the other -- the
        // single most important line in this effect for four-engine parity.
        // Every engine must compute the roll the same way, in the same place:
        // int(rand() * 100), compared < percent.
        //
        // percent defaults to 50, which is the coin flip the card asked for.
        // 0 never fires and 100 always does, both by construction rather than
        // by special case. `else` is optional: "half the time, nothing
        // happens" is a legitimate card.
        const pct  = Math.max(0, Math.min(100, Math.floor(eff.percent ?? 50)));
        const roll = Math.floor(rand() * 100);
        const hit  = roll < pct;
        push(`chance ${pct}%: ${hit ? 'HIT' : 'miss'}`);
        for (const sub of ((hit ? eff.then : eff['else']) || []))
          applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'add_counter': {
        // Goes on the permanent whose ability fired. Deliberately no targeting:
        // "put a time counter on Treasure Map" is the shape asked for, and a
        // counter placed on someone ELSE wants its own card and its own `side`
        // field to prove it.
        const ctag = String(eff.tag || '');
        if (!ctag)   { push(`add_counter with no tag — nothing to put on`); break; }
        if (!source) { push(`no permanent to put a [${ctag}] counter on`); break; }
        const got = addCounters(source, ctag, Math.max(1, Math.floor(a) || 1));
        const cnm = cardDefs[source.card_id]?.name || source.card_id;
        push(got > 0
          ? `+${got} [${ctag}] counter${got !== 1 ? 's' : ''} on ${cnm} (${source.counters[ctag]} total)`
          : `${cnm} is already at the ${MAX_COUNTERS_PER_ENTRY}-counter cap for [${ctag}]`);
        break;
      }

      case 'spend_counters_and': {
        const stag = String(eff.tag || '');
        const need = Math.max(1, Math.floor(a) || 1);
        if (!stag) { push(`spend_counters_and with no tag — nothing to spend`); break; }
        if (!spendCounters(ap, stag, need, !!eff.self, source)) {
          push(`fewer than ${need} [${stag}] counter${need !== 1 ? 's' : ''} — effect skipped`);
          break;
        }
        push(`spent ${need} [${stag}] counter${need !== 1 ? 's' : ''}${eff.self ? ' from this card' : ''}`);
        // v4.38 sacrifice_self: the second half of a two-part COST -- "remove
        // N counters from AND sacrifice this card". It happens after the
        // counters are actually spent (so a short pile pays nothing and keeps
        // the card) and before the `then`, because both are costs. Same
        // removal sequence sacrifice_and uses, so on_death/on_leave, the
        // champion zone and a token's exile all behave identically.
        if (eff.sacrifice_self && source && !source.destroyed) {
          source.destroyed = true;
          push(`sacrificed ${cardDefs[source.card_id]?.name || source.card_id}`);
          fireTriggerOn(source, 'on_death', ap, op, rand, cardDefs, log, plan, opPlan);
          fireTriggerOn(source, 'on_leave', ap, op, rand, cardDefs, log, plan, opPlan);
          ap.board = ap.board.filter(c => c !== source);
          if (!championLeave(ap, source.card_id, log, cardDefs))
            toDiscardOrExile(ap, source, cardDefs, rand, log, op, plan, opPlan);
        }
        for (const sub of (eff.then || [])) applyEffect(sub, ap, op, rand, cardDefs, log, plan, fromPassive, source, opPlan);
        break;
      }

      case 'manifest_card': {
        // v4.33 Manifest. Puts a REAL copy of a named card into a zone, where
        // it then behaves like any other card for the rest of the game: drawn,
        // played, discarded, milled, tutored and gravecast by every existing
        // path, with no special cases anywhere. That is the whole difference
        // from create_token_copy above -- the board entry it may eventually
        // become is NOT isToken, so it goes to the discard when it leaves play
        // instead of vanishing. See ENGINE_SPAWNED_CARDS.md.
        //
        // The card named is normally rarity='manifest' (deck_limit 0, so it
        // can never be BUILT into a deck), but ANY card id is legal here --
        // exactly as create_token_copy may point at a non-token card. The
        // token/manifest rarities are about what can be BUILT, not about what
        // may be copied, so manifesting into your own deck is a deck_limit
        // bypass by design and a card-balance question rather than a rules one.
        //
        // `destination` reuses tutor's existing vocabulary and struct field
        // rather than inventing a second one: play | hand | discard | deck
        // (deck is the default here, where tutor's is play).
        const amt   = Math.min(Math.max(1, a), MAX_TOKEN_COPIES_PER_FIRE);
        if (eff.random_from) {
          // v4.53: a random card from a library, copy by copy. The pool is
          // taken ONCE before any copy lands (so a copy manifested into the
          // same library can't be picked again), and each copy costs exactly
          // one rand() for the pick, then whatever its destination costs --
          // the same order in all four engines.
          const rOpp  = eff.side === 'opponent';
          const rTo   = rOpp ? op : ap, rFoe = rOpp ? ap : op;
          const rPlan = rOpp ? opPlan : plan, rFoePlan = rOpp ? plan : opPlan;
          const rWho  = rOpp ? "opponent's" : 'your';
          const rDest = eff.destination || 'deck';
          const pool  = randomFromPool(eff.random_from === 'opponent' ? op : ap, eff, cardDefs, rDest === 'play');
          if (!pool.length) { push(`  no matching card in ${eff.random_from === 'opponent' ? "opponent's" : 'your'} library to manifest`); break; }
          const band = Math.max(0, Math.floor(eff.band) || 0);
          for (let i = 0; i < amt; i++) {
            const cid = pool[Math.floor(rand() * pool.length)];
            const nm  = cardDefs[cid]?.name || cid;
            if (rDest === 'play') {
              reanimateOne(rTo, rFoe, cid, cardDefs, rand, log, 'thin air', false, rPlan, rFoePlan, rOpp);
            } else if (rDest === 'hand') {
              rTo.hand.push(cid);
              push(`manifested ${nm} into ${rWho} hand`);
            } else if (rDest === 'discard') {
              arriveInDiscard(rTo, cid, cardDefs, rand, log, 'tutor', rFoe, rPlan, rFoePlan);
              push(`manifested ${nm} into ${rWho} discard`);
            } else {
              insertIntoDeck(rTo, cid, band, rand);
              push(`manifested ${nm} into ${rWho} library${band > 0 ? ` among the top ${band}` : ''} (${rTo.deck.length} in library)`);
            }
          }
          break;
        }
        const mdef  = cardDefs[eff.card_id];
        if (!mdef) { push(`  no card found for card_id ${eff.card_id} to manifest`); break; }
        const mOpp  = eff.side === 'opponent';
        const mTo   = mOpp ? op : ap, mFoe = mOpp ? ap : op;
        const mPlan = mOpp ? opPlan : plan, mFoePlan = mOpp ? plan : opPlan;
        const mWho  = mOpp ? "opponent's" : 'your';
        const mName = mdef.name || eff.card_id;
        const mDest = eff.destination || 'deck';
        if (mDest === 'play') {
          // The same guard create_token_copy carries: a Spell has no board
          // presence, and pushing one onto a board parks it there forever
          // with no hp and nothing that can remove it.
          if (!isPermanent(mdef)) { push(`  ${mName} is not a permanent — cannot manifest it into play`); break; }
          for (let i = 0; i < amt; i++)
            reanimateOne(mTo, mFoe, eff.card_id, cardDefs, rand, log, 'thin air', false, mPlan, mFoePlan, mOpp);
        } else if (mDest === 'hand') {
          for (let i = 0; i < amt; i++) mTo.hand.push(eff.card_id);
          push(`manifested ${amt}x ${mName} into ${mWho} hand`);
        } else if (mDest === 'discard') {
          // Routed through arriveInDiscard so a Reshuffle printing, the
          // on_discard trigger and the watcher pair all see it, exactly as
          // they would for a card that got there any other way. Cause 'tutor'
          // is the existing "placed straight into the pile" route.
          for (let i = 0; i < amt; i++)
            arriveInDiscard(mTo, eff.card_id, cardDefs, rand, log, 'tutor', mFoe, mPlan, mFoePlan);
          push(`manifested ${amt}x ${mName} into ${mWho} discard`);
        } else {
          // A BAND is the first order-aware insert in this engine. Every other
          // deck insertion (recycle_self, tutor, Reshuffle, the discard sweep)
          // reshuffles the whole library, so nothing until now cared WHERE in
          // a deck a card landed. band N puts each copy at a uniformly random
          // index in [0, min(N, len)] -- band 1 is "on top" -- and consumes
          // EXACTLY ONE rand() call per copy, where a shuffle consumes len-1.
          // All four engines must consume the same count in the same order or
          // every shard of the next cohort comes back disputed.
          const band = Math.max(0, Math.floor(eff.band) || 0);
          for (let i = 0; i < amt; i++) insertIntoDeck(mTo, eff.card_id, band, rand);
          push(band > 0
            ? `manifested ${amt}x ${mName} into ${mWho} library among the top ${band} (${mTo.deck.length} in library)`
            : `manifested ${amt}x ${mName} into ${mWho} library (${mTo.deck.length} in library)`);
        }
        break;
      }

      case 'remove_opponent_energy': {
        // amount=0 means remove all; positive = remove up to that many
        const removeAll = literalZeroMeansAll(eff);
        const toRemove  = removeAll ? op.energy : Math.min(op.energy, a);
        op.energy = Math.max(0, op.energy - toRemove);
        push(removeAll
          ? `removed all ${toRemove} energy from opponent (${op.energy} remaining)`
          : `opponent -${toRemove} energy → ${op.energy}`);
        break;
      }

      // v4.40. The self-facing twin of remove_opponent_energy: a card that
      // costs its OWN controller energy, as a drawback or an ongoing price.
      //
      // BASIC FIRST, then ephemeral. The other order was considered and is
      // wrong: ephemeral clears at step 7 anyway, so an end_of_turn
      // "lose 1 energy" taking from the ephemeral pool would eat energy that
      // was about to vanish one step later and cost the player nothing. Basic
      // first means what the card says -- one less energy next turn.
      //
      // literal 0 = all of it, the same sentinel remove_opponent_energy uses.
      case 'lose_energy': {
        const loseAll = literalZeroMeansAll(eff);
        const want    = loseAll ? (ap.energy + ap.ephemeral) : Math.max(0, Math.floor(a) || 0);
        const fromBasic = Math.min(ap.energy, want);
        ap.energy -= fromBasic;
        const fromEph = Math.min(ap.ephemeral, want - fromBasic);
        ap.ephemeral -= fromEph;
        const lost = fromBasic + fromEph;
        push(loseAll
          ? `lost all ${lost} energy (${ap.energy} basic, ${ap.ephemeral} ephemeral)`
          : `-${lost} energy (${ap.energy} basic, ${ap.ephemeral} ephemeral)`);
        break;
      }

      case 'shuffle_permanent_into_deck': {
        // v4.34. A third removal axis: not destroy, not exile. The permanent
        // goes back into ITS OWNER's library, so it is not gone -- it will be
        // drawn again -- but it is off the board now and its discard pile
        // never sees it, which denies every recycle/gravecast payoff.
        //
        // Deliberately does NOT consult isIndestructible: that is checked at
        // damage and destroy sites only, and a bounce has always gone around
        // it (see return_opponent_permanent). Untargetable still protects,
        // exactly as it does for a bounce to hand. See ENGINE_SPAWNED_CARDS.md.
        const sOpp   = eff.side === 'opponent';
        const sOwner = sOpp ? op : ap, sFoe = sOpp ? ap : op;
        const sOwnPlan = sOpp ? opPlan : plan, sFoePlan = sOpp ? plan : opPlan;
        const sPref  = sOpp ? tpref : spref;
        const sFilter = eff.target_supertype || 'permanent';
        const sCount  = Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        const sBand   = Math.max(0, Math.floor(eff.band) || 0);
        // v4.47: an OPTIONAL cost gate, the same shape and the same neutral
        // default as exile_tagged's -- an ABSENT cost_op means NO filter, never
        // "match nothing". That inversion is what made four cards silently
        // inert in v4.00/v4.01, so it is spelled the same way in all four
        // engines. No live card carried a cost_op here before this version, so
        // the whole existing catalogue is unaffected.
        const sCostOK = e => !eff.cost_op || matchesCostFilter(cardDefs[e.card_id], '', eff.cost_op, eff.cost_threshold ?? 0);
        const sPool   = sOwner.board.filter(e => {
          if (e.destroyed) return false;
          const def = cardDefs[e.card_id];
          const matches = eff.tag ? entryHasTag(e, eff.tag, cardDefs) : matchesSupertype(def, sFilter);
          // Only an OPPONENT-facing shuffle is a targeting act; shuffling your
          // own permanent back is not, exactly as return_own_permanent does
          // not filter untargetable either.
          return matches && sCostOK(e) && (!sOpp || !isUntargetable(e, cardDefs));
        });
        if (!sPool.length) { push(`no ${sOpp ? 'targetable ' : ''}${eff.tag ? '[' + eff.tag + ']' : sFilter} to shuffle into a deck`); break; }
        let sDone = 0;
        while (sDone < sCount && sPool.length) {
          // the ACTING player picks the target, on either side -- same as the
          // return_*_permanent pair, which both read `plan` (not opPlan).
          const entry = selectTarget(filterByTagOrder(sPool, plan?.target_tag_order, cardDefs), sPref, rand, cardDefs);
          sPool.splice(sPool.indexOf(entry), 1);
          fireLeaveTrigger(entry, sOwner, sFoe, rand, cardDefs, log, true, sOwnPlan, sFoePlan);
          sOwner.board = sOwner.board.filter(e => e !== entry);
          if (!championLeave(sOwner, entry.card_id, log, cardDefs)) {
            const landed = toDeckOrExile(sOwner, entry, sBand, rand);
            const nm = cardDefs[entry.card_id]?.name || entry.card_id;
            push(!landed
              ? `  token copy of ${nm} exiled instead of going into a deck`
              : (sBand > 0
                  ? `shuffled ${nm} into ${sOpp ? "opponent's" : 'your'} deck among the top ${sBand} (${sOwner.deck.length} in library)`
                  : `shuffled ${nm} into ${sOpp ? "opponent's" : 'your'} deck (${sOwner.deck.length} in library)`));
          }
          sDone++;
        }
        break;
      }

      case 'return_opponent_permanent': {
        const filter  = eff.target_supertype || 'permanent';
        // tag takes precedence when set (same either/or rule as anthemBonus/
        // costModifierDelta), target_supertype is the fallback filter.
        const pool    = op.board.filter(e => {
          if (e.destroyed) return false;
          const def = cardDefs[e.card_id];
          const matches = eff.tag ? entryHasTag(e, eff.tag, cardDefs) : matchesSupertype(def, filter);
          return matches && !isUntargetable(e, cardDefs);
        });
        if (!pool.length) { push(`no targetable ${eff.tag ? '[' + eff.tag + ']' : filter} to return`); break; }
        // amount 0 = ALL (v4.45). Exempt from the 10 cap, like
        // exile_deck_and_manifest: the count is bounded by the board that was
        // just filtered, so it can never return more than is standing there.
        // Captured BEFORE the loop, which splices out of `pool` as it goes.
        const count   = literalZeroMeansAll(eff) ? pool.length
                      : Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let returned = 0;
        while (returned < count && pool.length) {
          const entry = selectTarget(filterByTagOrder(pool, plan?.target_tag_order, cardDefs), tpref, rand, cardDefs);
          pool.splice(pool.indexOf(entry), 1);
          fireLeaveTrigger(entry, op, ap, rand, cardDefs, log, true, opPlan, plan);
          op.board = op.board.filter(e => e !== entry);
          if (!championLeave(op, entry.card_id, log, cardDefs)) {
            toHandOrExile(op, entry);
            push(entry.isToken
              ? `  token copy of ${cardDefs[entry.card_id]?.name || entry.card_id} exiled instead of returning to hand`
              : `returned ${cardDefs[entry.card_id]?.name || entry.card_id} to opponent's hand`);
          }
          returned++;
        }
        break;
      }

      case 'return_own_permanent': {
        const filter  = eff.target_supertype || 'permanent';
        // tag takes precedence when set (same either/or rule as anthemBonus/
        // costModifierDelta), target_supertype is the fallback filter.
        const pool    = ap.board.filter(e => {
          if (e.destroyed) return false;
          const def = cardDefs[e.card_id];
          return eff.tag ? entryHasTag(e, eff.tag, cardDefs) : matchesSupertype(def, filter);
        });
        if (!pool.length) { push(`no ${eff.tag ? '[' + eff.tag + ']' : filter} to return to hand`); break; }
        // amount 0 = ALL (v4.45). See the note on return_opponent_permanent;
        // note this arm has no isUntargetable filter, deliberately -- you may
        // always bounce your OWN untargetable permanents.
        const count   = literalZeroMeansAll(eff) ? pool.length
                      : Math.min(Math.max(Math.floor(a) || 1, 1), 10);
        let returned = 0;
        while (returned < count && pool.length) {
          const entry = selectTarget(filterByTagOrder(pool, plan?.target_tag_order, cardDefs), spref, rand, cardDefs);
          pool.splice(pool.indexOf(entry), 1);
          fireLeaveTrigger(entry, ap, op, rand, cardDefs, log, true, plan, opPlan);
          ap.board = ap.board.filter(e => e !== entry);
          if (!championLeave(ap, entry.card_id, log, cardDefs)) {
            toHandOrExile(ap, entry);
            push(entry.isToken
              ? `  token copy of ${cardDefs[entry.card_id]?.name || entry.card_id} exiled instead of returning to hand`
              : `returned ${cardDefs[entry.card_id]?.name || entry.card_id} to your hand`);
          }
          returned++;
        }
        break;
      }

      case 'destroy_all': {
        const destroyAllPredicate = c => destroyAllMatches(c, eff, cardDefs);   // v4.69: shared with removalWouldHit()
        // .destroyed checked live -- see destroy_tagged's comment above.
        const toDestroy = op.board.filter(c => !c.destroyed && destroyAllPredicate(c));
        if (!toDestroy.length) { push('no matching permanents to destroy'); }
        else {
          const dBlocked = [];
          // Fire triggers before mutating op.board -- see destroy_tagged above.
          for (const t of toDestroy) {
            // Re-check .destroyed live, per-iteration -- see destroy_tagged
            // above for why (a nested cascade from an earlier iteration can
            // destroy a later entry still sitting in this pre-computed array).
            if (t.destroyed) continue;
            // v4.43 live indestructible check -- see destroy_tagged above.
            if (isIndestructible(t, cardDefs)) { dBlocked.push(t); continue; }
            t.destroyed = true;
            push(`destroyed ${cardDefs[t.card_id]?.name || t.card_id}`);
            fireLeaveTrigger(t, op, ap, rand, cardDefs, log, undefined, opPlan, plan);
            if (!championLeave(op, t.card_id, log, cardDefs)) toDiscardOrExile(op, t, cardDefs, rand, log, ap, opPlan, plan);
          }
          for (const t of dBlocked) push(`${cardDefs[t.card_id]?.name || t.card_id} is indestructible — not destroyed`);
        }
        // Re-filter unconditionally -- see destroy_tagged's comment above for why.
        op.board = op.board.filter(c => !c.destroyed);
        if (eff.symmetric) {
          const toDestroyOwn = ap.board.filter(c => !c.destroyed && destroyAllPredicate(c));
          if (!toDestroyOwn.length) { push('no matching permanents on your side to destroy'); }
          else {
            const dBlockedOwn = [];
            for (const t of toDestroyOwn) {
              if (t.destroyed) continue;
              // v4.43 live indestructible check -- see destroy_tagged above.
              if (isIndestructible(t, cardDefs)) { dBlockedOwn.push(t); continue; }
              t.destroyed = true;
              push(`destroyed your ${cardDefs[t.card_id]?.name || t.card_id}`);
              fireLeaveTrigger(t, ap, op, rand, cardDefs, log, undefined, plan, opPlan);
              if (!championLeave(ap, t.card_id, log, cardDefs)) toDiscardOrExile(ap, t, cardDefs, rand, log, op, plan, opPlan);
            }
            for (const t of dBlockedOwn) push(`your ${cardDefs[t.card_id]?.name || t.card_id} is indestructible — not destroyed`);
          }
          // See the unconditional re-filter comment above.
          ap.board = ap.board.filter(c => !c.destroyed);
        }
        break;
      }

      case 'destroy_permanents_by_cost': {
        const filter = eff.target_supertype || 'permanent';
        const costOp = eff.cost_op || 'lte';
        const thresh = eff.cost_threshold ?? 0;
        const destroyByCostPredicate = c => destroyByCostMatches(c, eff, cardDefs);   // v4.69: shared with removalWouldHit()
        // .destroyed checked live -- see destroy_tagged's comment above.
        const toDestroy = op.board.filter(c => !c.destroyed && destroyByCostPredicate(c));
        if (!toDestroy.length) { push(`no ${filter}s matching cost filter to destroy`); }
        else {
          const dBlocked = [];
          // Fire triggers before mutating op.board -- see destroy_tagged above.
          for (const t of toDestroy) {
            // Re-check .destroyed live, per-iteration -- see destroy_tagged
            // above for why (a nested cascade from an earlier iteration can
            // destroy a later entry still sitting in this pre-computed array;
            // confirmed live as the actual root cause of a "JS-only"
            // achievement-stat discrepancy this same fix pattern didn't
            // originally cover, 2026-08-03).
            if (t.destroyed) continue;
            // v4.43 live indestructible check -- see destroy_tagged above.
            if (isIndestructible(t, cardDefs)) { dBlocked.push(t); continue; }
            t.destroyed = true;
            const tdef = cardDefs[t.card_id];
            push(`destroyed ${tdef?.name || t.card_id} (cost ${tdef?.cost ?? 0})`);
            fireLeaveTrigger(t, op, ap, rand, cardDefs, log, undefined, opPlan, plan);
            if (!championLeave(op, t.card_id, log, cardDefs)) toDiscardOrExile(op, t, cardDefs, rand, log, ap, opPlan, plan);
          }
          for (const t of dBlocked) push(`${cardDefs[t.card_id]?.name || t.card_id} is indestructible — not destroyed`);
        }
        // Re-filter unconditionally -- see destroy_tagged's comment above for why.
        op.board = op.board.filter(c => !c.destroyed);
        if (eff.symmetric) {
          const toDestroyOwn = ap.board.filter(c => !c.destroyed && destroyByCostPredicate(c));
          if (!toDestroyOwn.length) { push(`no ${filter}s matching cost filter on your side to destroy`); }
          else {
            const dBlockedOwn = [];
            for (const t of toDestroyOwn) {
              if (t.destroyed) continue;
              // v4.43 live indestructible check -- see destroy_tagged above.
              if (isIndestructible(t, cardDefs)) { dBlockedOwn.push(t); continue; }
              t.destroyed = true;
              const tdef = cardDefs[t.card_id];
              push(`destroyed your ${tdef?.name || t.card_id} (cost ${tdef?.cost ?? 0})`);
              fireLeaveTrigger(t, ap, op, rand, cardDefs, log, undefined, plan, opPlan);
              if (!championLeave(ap, t.card_id, log, cardDefs)) toDiscardOrExile(ap, t, cardDefs, rand, log, op, plan, opPlan);
            }
            for (const t of dBlockedOwn) push(`your ${cardDefs[t.card_id]?.name || t.card_id} is indestructible — not destroyed`);
          }
          // See the unconditional re-filter comment above.
          ap.board = ap.board.filter(c => !c.destroyed);
        }
        break;
      }

      case 'deal_damage_to_permanents_by_cost': {
        const filter = eff.target_supertype || 'permanent';
        const costOp = eff.cost_op || 'lte';
        const thresh = eff.cost_threshold ?? 0;
        const amp = a + sumCombatKW(ap.board, 'amplify_damage', cardDefs);
        let hit = 0;
        for (const c of op.board) {
          const def = cardDefs[c.card_id] ?? {};
          if (!matchesSupertype(def, filter)) continue;
          const cost = def.cost ?? 0;
          const matches = costOp === 'lt'  ? cost <  thresh
                        : costOp === 'lte' ? cost <= thresh
                        : costOp === 'gt'  ? cost >  thresh
                        : costOp === 'gte' ? cost >= thresh
                        : costOp === 'eq'  ? cost === thresh : false;
          if (!matches || c.hp === null || c.hp <= 0) continue;
          hit++;
          if (isIndestructible(c, cardDefs)) { push(`${def.name || c.card_id} (cost ${cost}) is indestructible — no damage`); continue; }
          const abs = dmgCreature(c, amp);
          push(`dealt ${amp} to ${def.name || c.card_id} (cost ${cost})${abs > 0 ? ` (${abs} absorbed)` : ''} → ${c.hp} hp${amp > a ? ' (amplified)' : ''}`);
        }
        if (!hit) push(`no damageable ${filter}s matching cost filter`);
        if (eff.symmetric) {
          let hitOwn = 0;
          for (const c of ap.board) {
            const def = cardDefs[c.card_id] ?? {};
            if (!matchesSupertype(def, filter)) continue;
            const cost = def.cost ?? 0;
            const matches = costOp === 'lt'  ? cost <  thresh
                          : costOp === 'lte' ? cost <= thresh
                          : costOp === 'gt'  ? cost >  thresh
                          : costOp === 'gte' ? cost >= thresh
                          : costOp === 'eq'  ? cost === thresh : false;
            if (!matches || c.hp === null || c.hp <= 0) continue;
            hitOwn++;
            if (isIndestructible(c, cardDefs)) { push(`your ${def.name || c.card_id} (cost ${cost}) is indestructible — no damage`); continue; }
            const abs = dmgCreature(c, amp);
            push(`dealt ${amp} to your ${def.name || c.card_id} (cost ${cost})${abs > 0 ? ` (${abs} absorbed)` : ''} → ${c.hp} hp${amp > a ? ' (amplified)' : ''}`);
          }
          if (!hitOwn) push(`no damageable ${filter}s matching cost filter on your side`);
        }
        break;
      }
    }
  }

  // ── Passive helpers ───────────────────────────────────────────────────────────
  // Check whether player has at least one passive of a given type in play.
  function hasPassive(player, passiveType, cardDefs) {
    return player.board.some(e => {
      const ps = activePassives(e, cardDefs);            // v4.22 mute chokepoint
      return ps && ps.some(p => p.type === passiveType);
    });
  }

  // Sum the `amount` of a combat keyword across all non-utilized permanents.
  // Used for link/bound passives that scale by combat damage.
  function sumCombatKW(attackers, kw, cardDefs) {
    let total = 0;
    for (const e of attackers) {
      const ps = activePassives(e, cardDefs);            // v4.22 mute chokepoint
      if (!ps) continue;
      for (const p of ps) {
        // `|| 1` (not `?? 1`): matches Go/Python's coercion of an explicit
        // 0 up to 1, same as an omitted amount -- Go's Amount field is a
        // plain float64, indistinguishable from 0 when the JSON key is
        // missing, so it can't tell "no amount given" from "amount: 0"
        // without a wider struct change. No real card has ever set an
        // explicit 0 here (would be a no-op keyword by design), so this
        // only affects a case that's never actually occurred -- matching
        // Go/Python's simpler behavior here is the lower-risk fix.
        if (p.type === kw) total += (p.amount || 1);
      }
    }
    return total;
  }

  // sumCombatKWScaled computes the CEILING (pre-combatDmg-cap) for a
  // melee-scaled combat-link/bound keyword: sum over attackers holding
  // passive `kw` of (that attacker's live melee x that attacker's own
  // amount/count of kw). Callers apply Math.min(result, combatDmg)
  // themselves next to the existing combatDmg > 0 gate, so this stays a
  // pure/reusable ceiling calculation, not the final applied amount.
  // meleeOf must be a parallel array to attackers (same index order),
  // holding each attacker's already-computed live melee (base +
  // anthem_melee) -- exactly the per-entry value already summed into
  // totalMelee at the combat-block call site, computed in the same loop
  // rather than recomputing anthemBonus per keyword per call. Same
  // `|| 1` amount coercion as sumCombatKW -- required for 3-engine parity
  // with shell/validate.go / shell/validate.py.
  function sumCombatKWScaled(attackers, meleeOf, kw, cardDefs) {
    let total = 0;
    for (let i = 0; i < attackers.length; i++) {
      const e = attackers[i];
      const ps = activePassives(e, cardDefs);            // v4.22 mute chokepoint
      // NB: no `continue` when ps is missing. A recipient with no passives of
      // its own -- a vanilla Drone -- must still collect GRANTED instances
      // below, and Go/Python both fall through to that line. Skipping the whole
      // iteration here would have made this engine disagree with them for
      // exactly the commonest case.
      if (ps) {
        for (const p of ps) {
          if (p.type === kw) total += meleeOf[i] * (p.amount || 1);
        }
      }
      // Granted instances are worth 1 each and scale by the RECIPIENT's own
      // basis, exactly like a printed one. meleeOf is the caller's linkCapOf,
      // so non-creatures keep their cost substitution.
      if (GRANTABLE_SCALED_KEYWORDS.has(kw)) {
        total += meleeOf[i] * grantedKeywordCount(e, kw, cardDefs);
      }
    }
    return total;
  }

  // linkCapBasis returns the value sumCombatKWScaled should multiply a
  // given attacker's link/bound amount by: normally liveMelee, EXCEPT for
  // non-creature permanents (Relic/Structure/Enchantment), which have no
  // melee stat at all (always 0) -- a link keyword on one would be
  // permanently dead under a pure-melee basis. 2026-08-08 errata: non-
  // creature permanents use their own cost instead (e.g. Void Siphon, a
  // 5-cost Relic with drawlink, caps at 5x its amount rather than never
  // triggering). Only affects the LINK-KEYWORD ceiling -- real combat
  // damage (totalMelee/combatDmg) still uses each entry's actual melee (0
  // for non-creatures), unaffected by this function.
  function linkCapBasis(cardId, liveMelee, cardDefs) {
    const cd = cardDefs[cardId];
    if (cd && !matchesSupertype(cd, 'creature')) return cd.cost || 0;
    return liveMelee;
  }

  // matchesCostFilter is the same supertype+cost-comparison check
  // destroy_permanents_by_cost/deal_damage_to_permanents_by_cost use, factored
  // out so the siegelink/reliclink/enchantmentlink combat-link keywords can
  // reuse it without an eff object to read target_supertype/cost_op/
  // cost_threshold from -- those keywords pass the filter/op/threshold as
  // plain values instead.
  // Applies an effect's OPTIONAL target_supertype and cost_op/cost_threshold to
  // a candidate pool. Neither set = pool untouched, which is every card written
  // before v3.87. See validate.go's narrowByTypeAndCost.
  function narrowByTypeAndCost(pool, eff, cardDefs) {
    const sup = eff.target_supertype || '', op_ = eff.cost_op || '';
    if (!sup && !op_) return pool;
    const s = sup || 'permanent';
    return pool.filter(e => {
      const cd = cardDefs[e.card_id];
      if (!matchesSupertype(cd, s)) return false;
      if (op_ && !matchesCostFilter(cd, s, op_, eff.cost_threshold || 0)) return false;
      return true;
    });
  }

  // v4.53 random_from: the candidates for a create_token_copy / manifest_card
  // that copies a RANDOM card from a library instead of a named one. Cards LEFT
  // in that library only (Ryan, 2026-10-31), one entry per copy, so a card with
  // three copies left is three times as likely. `tag` '' means any tag; an
  // absent cost_op means no cost filter. The library is not changed -- this
  // copies, it never takes.
  function randomFromPool(fromP, eff, cardDefs, needPerm) {
    const tag = eff.tag || '';
    return fromP.deck.filter(cid => {
      const def = cardDefs[cid];
      if (!def) return false;
      if (tag !== '' && !(def.tags ?? []).includes(tag)) return false;
      if (eff.cost_op && !matchesCostFilter(def, '', eff.cost_op, eff.cost_threshold ?? 0)) return false;
      if (needPerm && !isPermanent(def)) return false;
      return true;
    });
  }

  function matchesCostFilter(cd, supertype, costOp, threshold) {
    // An EMPTY supertype means "no supertype constraint" -- NOT "match
    // nothing". matchesSupertype() does a word-membership test and '' is
    // never a word, so it was false for every card, turning the cost filter
    // into a kill switch at the two callers that pass '' (the stat-counter
    // effects and return_tagged_to_hand). See validate.go's matchesCostFilter
    // for the full writeup; cards 961 and 956 were inert because of it.
    if (supertype !== '') {
      if (!matchesSupertype(cd, supertype)) return false;
    } else if (!cd) {
      return false;
    }
    const cost = cd?.cost ?? 0;
    if (costOp === 'lt')  return cost <  threshold;
    if (costOp === 'lte') return cost <= threshold;
    if (costOp === 'gt')  return cost >  threshold;
    if (costOp === 'gte') return cost >= threshold;
    if (costOp === 'eq')  return cost === threshold;
    return false;
  }

  // Sum the anthem-style aura bonus a given board entry (or about-to-enter card_id)
  // receives from OTHER entries on the same board carrying `passiveType` (e.g.
  // anthem_melee/anthem_armor). Each source picks its recipients by its
  // target_supertype filter (default "creature"), narrowed by `tag` when one is
  // set (v4.67 -- before that a tag replaced the supertype filter outright). A
  // source never buffs itself.
  // effectiveMelee: see shell/validate.go's effectiveMelee for why this
  // exists. Reproduces THIS engine's existing expression verbatim.
  function effectiveMelee(e, board, cardDefs) {
    let m = (e.melee || 0) + anthemBonus(e.card_id, board, cardDefs, 'anthem_melee', e);
    // v3.99 melee counters. melee_locked pins melee to its printed value, so
    // counters are ignored for the same reason anthemBonus returns 0.
    const def = cardDefs[e.card_id];
    if (!def || !hasCardPassive(def, 'melee_locked')) m += (e.meleeCounters || 0);
    // A creature never attacks for less than nothing -- clamped PER CREATURE,
    // so one debuffed body cannot cancel a healthy one's melee.
    return m > 0 ? m : 0;
  }
  // effectiveArmor: see shell/validate.go. Armor is read in TWO structurally
  // different places -- the per-turn curArmor rebuild and totalOpArmor in
  // combat, which never looks at curArmor -- so both go through here. The 0
  // floor matters: damageEntry subtracts `absorbed` from the hit, so a
  // negative value AMPLIFIES damage rather than failing to absorb.
  // v4.62 Flying / v4.63 Shieldbreaker (named Piercing in 4.62). Combat is one pooled sum: every attacker's melee
  // against the opponent's total armor plus shields. Flying melee is blocked
  // only by the armor of the opponent's Flying permanents; Shieldbreaker melee is
  // not blocked by shields. Armor and shields are allocated the way that
  // blocks the MOST melee (Ryan, 2026-11-13): ground armor (non-Flying
  // permanents) goes first to Shieldbreaker-only melee, the one thing it can stop
  // that shields cannot, then to plain melee; shields go first to Flying-only
  // melee, then to plain melee; Flying armor can block anything, so it is
  // spent last on whatever is left. With no Flying or Shieldbreaker attacker this
  // returns null and the caller keeps the old formula (which may go negative,
  // and the log prints that), so every existing match is byte-identical.
  function combatWithEvasion(attackers, meleeOf, op, cardDefs) {
    let N = 0, F = 0, P = 0, FP = 0, flyM = 0, pierceM = 0, any = false;
    attackers.forEach((e, i) => {
      const m = meleeOf[i];
      const fl = entryHasKeyword(e, 'flying', cardDefs), pi = entryHasKeyword(e, 'shieldbreaker', cardDefs);
      if (fl) flyM += m;
      if (pi) pierceM += m;
      if (fl || pi) any = true;
      if (fl && pi) FP += m; else if (fl) F += m; else if (pi) P += m; else N += m;
    });
    if (!any) return null;
    N = Math.max(0, N); F = Math.max(0, F); P = Math.max(0, P); FP = Math.max(0, FP);
    let ag = 0, af = 0;
    for (const e of op.board) {
      if (e.utilized) continue;
      const a = effectiveArmor(e, op.board, cardDefs);
      // v4.66 Reach: its armor joins the Flying pool -- it can block flying
      // melee, and like Flying armor it still blocks everything else.
      if (entryHasKeyword(e, 'flying', cardDefs) || entryHasKeyword(e, 'reach', cardDefs)) af += a; else ag += a;
    }
    ag = Math.max(0, ag); af = Math.max(0, af);
    let sh = Math.max(0, op.shields), b;
    b = Math.min(ag, P);  P -= b;  ag -= b;
    b = Math.min(ag, N);  N -= b;
    b = Math.min(sh, F);  F -= b;  sh -= b;
    b = Math.min(sh, N);  N -= b;
    const rest = N + F + P + FP;
    return { dmg: rest - Math.min(af, rest), flyingMelee: flyM, shieldbreakerMelee: pierceM, flyingArmor: af };
  }

  function effectiveArmor(e, board, cardDefs) {
    const v = (e.armor || 0) + anthemBonus(e.card_id, board, cardDefs, 'anthem_armor', e) + (e.armorCounters || 0);
    return v > 0 ? v : 0;
  }
  function anthemBonus(cardId, board, cardDefs, passiveType, excludeEntry) {
    const def = cardDefs[cardId];
    // melee_locked: see the comment on Go's anthemBonus. Melee is pinned to the
    // printed value, so anthem_melee contributes 0 in either direction;
    // anthem_armor is unaffected.
    if (passiveType === 'anthem_melee' && hasCardPassive(def, 'melee_locked')) return 0;
    const recip = excludeEntry && excludeEntry.card_id === cardId ? excludeEntry : null;
    let bonus = 0;
    for (const src of board) {
      if (src === excludeEntry) continue;
      const ps = activePassives(src, cardDefs);          // v4.22 mute chokepoint
      if (!ps) continue;
      for (const p of ps) {
        if (p.type !== passiveType) continue;
        // v4.67: tag AND target_supertype (see Go's anthemBonus).
        // v4.68: the tag through entryHasTag when we know the recipient.
        const matches = (!p.tag || (recip ? entryHasTag(recip, p.tag, cardDefs) : (def?.tags ?? []).includes(p.tag)))
          && matchesSupertype(def, p.target_supertype || 'creature');
        // `|| 1` (not `?? 1`): matches sumCombatKW's coercion of an
        // explicit 0 up to 1, same as a missing amount -- `?? 1` only
        // catches null/undefined, silently leaving an explicit 0 as a
        // no-op bonus instead of the intended +1. Found via the fake-card
        // sweep's negative-amount anthem_melee passive (a separate gap:
        // `?? 1` correctly leaves negative amounts alone, so only the
        // explicit-0 case was actually wrong here).
        if (matches) bonus += (p.amount || 1);
      }
    }
    return bonus;
  }

  // Sum the signed cost_modifier passives that apply to def, which ap is
  // about to play: sources on ap's own board with target_player "self"
  // (default) tax/discount ap's own matching cards, while sources on op's
  // board with target_player "opponent" tax/discount ap's matching cards
  // (op is ap's opponent, so "opponent" from op's perspective is ap).
  // Matching is by tag (if set, takes precedence) or target_supertype
  // (default "creature"), same either/or rule as anthemBonus. Unlike
  // anthemBonus, the amount is signed and never defaulted to 1.
  //
  // Also tracks `minimum`: an optional per-passive floor (e.g. "reduce
  // spells by 1, to a minimum of 1 energy"). When several cost_modifier
  // passives match the same card, the effective floor is the MAX of every
  // matching passive's minimum (default 0) -- not a per-source clamp -- so
  // a card's stated protection ("never below 1") holds regardless of what
  // else is discounting/taxing it. Returned alongside delta since the floor
  // must be applied to the final summed cost, not to each passive in
  // isolation.
  function costModifierDelta(def, ap, op, cardDefs) {
    let delta = 0;
    let minimum = 0;
    for (const src of ap.board) {
      const ps = activePassives(src, cardDefs);          // v4.22 mute chokepoint
      if (!ps) continue;
      for (const p of ps) {
        if (p.type !== 'cost_modifier') continue;
        if ((p.target_player || 'self') !== 'self') continue;
        const matches = p.tag
          ? (def?.tags ?? []).includes(p.tag)
          : matchesSupertype(def, p.target_supertype || 'creature');
        if (matches) {
          delta += (p.amount || 0);
          minimum = Math.max(minimum, p.minimum || 0);
        }
      }
    }
    for (const src of op.board) {
      const ps = activePassives(src, cardDefs);          // v4.22 mute chokepoint
      if (!ps) continue;
      for (const p of ps) {
        if (p.type !== 'cost_modifier' || p.target_player !== 'opponent') continue;
        const matches = p.tag
          ? (def?.tags ?? []).includes(p.tag)
          : matchesSupertype(def, p.target_supertype || 'creature');
        if (matches) {
          delta += (p.amount || 0);
          minimum = Math.max(minimum, p.minimum || 0);
        }
      }
    }
    return { delta, minimum };
  }

  // Fire all passives of a given type owned by `player`.
  // ap/op define the effect direction (ap = beneficiary, op = target of negative effects).
  // fromPassive: whether the gain/event THIS passive would react to was
  // itself already the result of a passive's effect. If not, every matching
  // passive fires unconditionally (today's behavior, no change). If so, a
  // passive only fires when it opts in via `loop_limit` (a per-turn budget,
  // tracked per board entry + passive type so two copies of the same card
  // each get their own independent count) -- otherwise it's the same
  // one-hop suppression as before. This is what lets specifically-marked
  // cards (e.g. Champion of Nothing / Honor Helix) chain a bounded number
  // of times instead of either never chaining or looping forever.
  // `utilizedEntry` (v4.07) is set ONLY by the three utilize sites, and is the
  // board entry that was just tapped. It unlocks two refinements that need to
  // know WHICH permanent the event was about -- something a broadcast by
  // passive type cannot otherwise tell:
  //
  //   on_self_utilized   fires only on the tapped card itself (e === entry)
  //   p.tag              on on_utilized / on_opponent_utilized, fires only when
  //                      the tapped permanent carries that tag
  //
  // Every other caller passes nothing and is unaffected.
  function firePassives(player, passiveType, ap, op, rand, cardDefs, log, fromPassive, plan, opPlan, utilizedEntry) {
    // Snapshot before iterating -- matches shell/validate.go's own comment
    // on this exact function: a matching passive's own effect can ADD a new
    // entry to player.board mid-loop (e.g. reanimating a card that has the
    // same passive type), and a bare `for...of` re-checks the live array's
    // length each iteration, so the newly-added entry would get visited and
    // fire too -- reacting to an event that happened before it existed. Go
    // already snapshots here; this was the one real, fixable cause behind
    // the fake-card sweep's "MAX_EFFECT_DEPTH" red herring (Go's own trace
    // never came close to the depth cap -- the actual divergence was Go
    // correctly excluding a just-reanimated card from a broadcast already
    // in progress, Python/JS incorrectly including it).
    for (const e of player.board.slice()) {
      const ps = activePassives(e, cardDefs);            // v4.22 mute chokepoint
      if (!ps) continue;
      for (const p of ps) {
        if (p.type !== passiveType || !p.effect) continue;
        if (utilizedEntry) {
          // "THIS card was tapped", by board-entry identity -- not card id, so
          // a second copy of the same card does not fire for its twin.
          if (passiveType === 'on_self_utilized' && e !== utilizedEntry) continue;
          // An OPTIONAL tag filter. The truthiness test is the whole point and
          // is deliberate: an absent or empty tag means "any permanent", NOT a
          // filter that matches nothing. v4.00/v4.01 shipped exactly that
          // inversion for an empty supertype and then an empty tag, and four
          // cards went silently inert in all four engines at once.
          if (p.tag && !entryHasTag(utilizedEntry, p.tag, cardDefs)) continue;
        }
        // ── Per-turn caps (v4.27) ──────────────────────────────────────
        // Optional, additive, and unlike loop_limit they are honoured whether
        // or not the event came from another passive -- loop_limit only ever
        // bounds passive->passive chains, which is why an organic event
        // (combat damage, a card being played) could fire an uncapped passive
        // without limit. Absent or <= 0 means "no cap", matching loop_limit's
        // own convention, so every card that omits them is unaffected.
        //   max_per_turn        -- budget per BOARD ENTRY (each copy its own)
        //   max_per_turn_shared -- budget per CARD ID per player (all copies
        //                          share one), which is the only form that
        //                          actually bounds a deck stacking copies
        // Both must pass when both are set.
        if (p.max_per_turn_shared > 0) {
          player.mptShared = player.mptShared || {};
          const sk = e.card_id + ':' + passiveType;
          const su = player.mptShared[sk] || 0;
          if (su >= p.max_per_turn_shared) continue;
          player.mptShared[sk] = su + 1;
        }
        if (p.max_per_turn > 0) {
          e.loopCounts = e.loopCounts || {};
          const mk = 'mpt:' + passiveType;
          const mu = e.loopCounts[mk] || 0;
          if (mu >= p.max_per_turn) continue;
          e.loopCounts[mk] = mu + 1;
        }
        if (fromPassive) {
          if (!p.loop_limit) continue;
          e.loopCounts = e.loopCounts || {};
          const used = e.loopCounts[passiveType] || 0;
          if (used >= p.loop_limit) continue;
          e.loopCounts[passiveType] = used + 1;
        }
        // Name the REACTING card. A passive fired in response to another
        // card's effect used to print with no marker at all, directly under
        // the marker of the card that woke it, so the viewer credited the
        // wrong card -- Crystal Vial's "+2 life" woke Deceptive Friend and the
        // mill read as the Vial's doing (reported 2026-10-13).
        markSrc(log, e);
        applyEffect(p.effect, ap, op, rand, cardDefs, log, plan, true, e, opPlan);
      }
    }
  }

  // Tag-watcher passives (on_tag_enter/on_tag_leave/on_tag_death, and their
  // on_opponent_tag_* twins): react when ANY permanent whose tags include
  // the watcher's configured `tag` enters/leaves/dies on a given board.
  // `watcherOwner`/`watcherOpp` are whose board gets scanned for matching
  // passives (and who's the effect's beneficiary/opponent pair); `entryOwner`
  // is whose board `entry` actually belongs to -- for the own-board variants
  // these are the same player (mirroring every other passive's self-only
  // scope, e.g. on_life_loss/on_poisoned), for the on_opponent_tag_* variants
  // they're deliberately different (watcherOwner watches entryOwner's board).
  // `entry` is the permanent that just entered/left; guarding on board
  // membership excludes the fake {card_id} pseudo-entry used for a spell's
  // on_enter fire, since spells never actually enter play as a permanent.
  // The *_tag_played variants are the exception -- defined to fire for ANY
  // card played (permanent or spell), specifically so spells can trigger
  // tag-watcher reactions too, so they skip the board-membership requirement
  // entirely.
  // effectCreatesBoardEntry mirrors every applyEffect case that appends a
  // new entry to a board. tutor only counts when it resolves to play --
  // undefined/'' defaults to play, same as the tutor case itself.
  function effectCreatesBoardEntry(eff) {
    if (eff.type === 'create_token_copy' || eff.type === 'reanimate_tagged' || eff.type === 'reanimate_random_tagged') return true;
    if (eff.type === 'tutor') return !eff.destination || eff.destination === 'play';
    return false;
  }

  // maxTagWatcherEntries caps how many times a single tag-watcher passive may
  // create a new board entry over one game. Unlike loopCounts (the opt-in
  // Loops N budget above), tag-watcher passives fire on every qualifying play
  // with no per-turn reset, so an unbounded one can grow the board every turn
  // and fireTagWatchers rescans the whole board on every fire -- compounding
  // into a multi-hour CPU spin in a long game (confirmed live via a stuck
  // validator's goroutine dump). 20 is generous for reactive punish effects.
  const maxTagWatcherEntries = 20;

  function fireTagWatchers(watcherOwner, watcherOpp, entryOwner, entry, passiveType, rand, cardDefs, log, plan, opPlan) {
    const isPlayedType = passiveType === 'on_tag_played' || passiveType === 'on_opponent_tag_played';
    const isEnterType  = passiveType === 'on_tag_enter'  || passiveType === 'on_opponent_tag_enter';
    // An enter-type watcher must still fire when the entering permanent left
    // play during its OWN on_enter -- fireTrigger runs the card's own triggers
    // before these watchers, so Eccentric Tutor (#923) bouncing itself, or any
    // creature sacrificing itself, removed it from the board first and Energy
    // Banner (#737) silently saw nothing. Reported by Fry Guy 2026-09-25.
    //
    // Board membership was only ever a proxy for "is this a real permanent".
    // The thing it has to exclude is the transient fake entry a SPELL's
    // on_enter fires through (see playOneCard's else branch) -- that entry is
    // never given an iid, while every real board entry takes one from
    // iidCounter. So test provenance, not current location. Leave/death types
    // keep the board check: those fire while the entry is still on the board,
    // and relaxing them would let a removed entry fire twice.
    if (!isPlayedType) {
      if (isEnterType) { if (!entry.iid) return; }
      else if (!entryOwner.board.includes(entry)) return;
    }
    const tags = entryTags(entry, cardDefs);
    // v4.68: a watcher's tag also matches a live keyword (entryHasTag), so an
    // entry with no printed tags can still be watched for "flying".
    // Snapshot before iterating -- same reasoning as firePassives above and
    // shell/validate.go's own comment on this function: a matching
    // watcher's own effect can add a new entry to watcherOwner.board
    // mid-loop, which a bare `for...of` would then visit and fire too.
    for (const e of watcherOwner.board.slice()) {
      const ps = activePassives(e, cardDefs);            // v4.22 mute chokepoint
      if (!ps) continue;
      for (const p of ps) {
        if (p.type === passiveType && p.tag && (tags.includes(p.tag) || entryHasTag(entry, p.tag, cardDefs)) && p.effect) {
          // Same per-turn caps as firePassives above. This path had NO budget
          // of any kind before v4.27 -- loop_limit is never consulted here, and
          // maxTagWatcherEntries below only bounds effects that create a board
          // entry, never damage/honor/life. 58 live cards sat unbounded.
          if (p.max_per_turn_shared > 0) {
            watcherOwner.mptShared = watcherOwner.mptShared || {};
            const sk = e.card_id + ':' + passiveType;
            const su = watcherOwner.mptShared[sk] || 0;
            if (su >= p.max_per_turn_shared) continue;
            watcherOwner.mptShared[sk] = su + 1;
          }
          if (p.max_per_turn > 0) {
            e.loopCounts = e.loopCounts || {};
            const mk = 'mpt:' + passiveType;
            const mu = e.loopCounts[mk] || 0;
            if (mu >= p.max_per_turn) continue;
            e.loopCounts[mk] = mu + 1;
          }
          if (effectCreatesBoardEntry(p.effect)) {
            e.tagCreateCounts = e.tagCreateCounts || {};
            const used = e.tagCreateCounts[passiveType] || 0;
            if (used >= maxTagWatcherEntries) continue;
            e.tagCreateCounts[passiveType] = used + 1;
          }
          // Name the watcher, so a tag reaction need not be guessed back from
          // the board (replay2's _resolveReactive abstains whenever two cards
          // could have produced the line).
          markSrc(log, e);
          applyEffect(p.effect, watcherOwner, watcherOpp, rand, cardDefs, log, plan, true, e, opPlan);
        }
      }
    }
  }

  // ── Trigger helpers ───────────────────────────────────────────────────────────
  // Fire one event on one board entry. plan/opPlan are optional; when set,
  // on_enter targeting (and on_tag_played/on_tag_enter watchers) use them --
  // plan for the entry owner's own watchers, opPlan for the opponent-facing twins.
  // isBounce marks an on_leave caused by returning to hand (not a "death") -- tag-death
  // watchers skip it, tag-leave watchers still fire for it.
  function fireTriggerOn(bc, event, ap, op, rand, cardDefs, log, plan, opPlan, isBounce) {
    const def = cardDefs[bc.card_id];
    // v4.22 Mute: the trigger half of the chokepoint. A muted permanent fires
    // NOTHING, on_death and on_leave included -- kkoechel's call, and it is
    // what makes Mute an answer to death-trigger decks rather than only to
    // passives. Mute 0 is still in force at the moment the permanent leaves,
    // so its on_leave is suppressed too.
    if (isMuted(bc)) return;
    if (def?.effects_json?.triggers) {
      // Player-attribute this trigger's log lines explicitly, mirroring how
      // firePassives() call sites already wrap passive-fired lines with an
      // "[event] playerN: " prefix -- battle-visualizer.js's _parseSegment()
      // already generically parses ANY "[on_xxx] playerN: ..." line this way,
      // so no visualizer change is needed. Without this, the visualizer's
      // fallback (label every unprefixed line with whichever player's turn
      // it currently is) misattributes any trigger firing for the NON-active
      // player -- e.g. an opponent's kill_target destroying this card, whose
      // on_death gain_honor already correctly credits THIS card's real owner
      // at the game-state level (ap here IS that owner, for every call site:
      // pruneBoard's on_death/on_leave and kill_target/etc.'s inline firing
      // all already pass the dying/leaving card's real controller as ap) --
      // only the human-readable log line was showing the wrong name.
      // Confirmed live 2026-08-10 (Eastern Bug Collector's honor gain shown
      // under the opponent's name). §src: markers are pushed unprefixed, on
      // purpose -- the damage-bump animation parser requires them to start
      // the line exactly.
      const prefix = (ap.pIndex === 0 || ap.pIndex === 1) ? `player${ap.pIndex + 1}: ` : '';
      for (const t of def.effects_json.triggers) {
        if (t.event !== event) continue;
        // Unconditional, NOT markSrc: one marker per trigger is the shape the
        // viewer's "first line under a marker is its own" rule is built on.
        // Deduping here folded two triggers of one card into a single marker
        // and demoted 145 of Ghastly Advisor's own lines to "later in block".
        if (log) { log.push(`§src:${bc.card_id}`); log.__src = bc.card_id; }
        const sub = (log && prefix) ? [] : log;
        if (sub !== log) sub.__src = log.__src;  // the sub-buffer continues this one
        applyEffect(t.effect, ap, op, rand, cardDefs, sub, plan, undefined, bc, opPlan);
        if (sub !== log) {
          // Effects can nest (e.g. kill_target's on_enter firing another
          // card's on_death mid-resolution) -- a line already carrying its
          // own "§src:"/"[on_xxx] playerN:" attribution came from an inner
          // fireTriggerOn call and is already correctly formed; wrapping it
          // again would double-nest the prefix and break the visualizer's
          // (non-recursive) parser. Only bare, unattributed lines get this
          // level's prefix.
          for (const l of sub) {
            const t2 = l.trim();
            log.push(t2.startsWith('§src:') || t2.startsWith('[on_') || t2.startsWith('[chapter]') ? l : `  [${event}] ${prefix}${t2}`);
          }
          // Markers were copied across verbatim, so the outer log now ends on
          // whichever source the sub-buffer ended on.
          log.__src = sub.__src;
        }
      }
    }
    if (event === 'on_enter') {
      fireTagWatchers(ap, op, ap, bc, 'on_tag_enter', rand, cardDefs, log, plan, opPlan);
      fireTagWatchers(op, ap, ap, bc, 'on_opponent_tag_enter', rand, cardDefs, log, opPlan, plan);
    }
    if (event === 'on_leave') {
      // v3.42: on_tag_leave/on_tag_death now correctly thread plan/opPlan --
      // this branch was out of scope for the v3.39 on_tag_played/on_tag_enter
      // fix because the leaving permanent's owner isn't reliably ap here (e.g.
      // kill_target hits the opponent's board), but callers now pass the
      // correctly-swapped plan/opPlan pair (see fireLeaveTrigger and its call
      // sites in applyEffectImpl), so plan/opPlan are trustworthy here too.
      fireTagWatchers(ap, op, ap, bc, 'on_tag_leave', rand, cardDefs, log, plan, opPlan);
      fireTagWatchers(op, ap, ap, bc, 'on_opponent_tag_leave', rand, cardDefs, log, opPlan, plan);
      if (!isBounce) {
        fireTagWatchers(ap, op, ap, bc, 'on_tag_death', rand, cardDefs, log, plan, opPlan);
        fireTagWatchers(op, ap, ap, bc, 'on_opponent_tag_death', rand, cardDefs, log, opPlan, plan);
      }
    }
    // v4.52 Saga: chapter I runs as the Saga arrives, after its own on_enter
    // abilities and the enter watchers. Every route onto a board fires
    // on_enter here, so this one hook covers cast, reanimate, token copy,
    // manifest-to-play, gravecast and champion alike. `chapter` is only ever
    // 0 before the first chapter, so nothing can re-run chapter I.
    if (event === 'on_enter' && !(bc.chapter > 0) && sagaPassive(def))
      advanceSaga(bc, ap, op, rand, cardDefs, log, plan, opPlan);
  }

  // Fire one event across an ordered list of board entries.
  // Prunes dead creatures after each entry so later triggers can't re-target them.
  function fireTriggers(entries, event, ap, op, rand, cardDefs, log, plan, opPlan) {
    for (const bc of entries) {
      fireTriggerOn(bc, event, ap, op, rand, cardDefs, log, plan);
      pruneBoard(ap, op, rand, cardDefs, log, plan, opPlan);
      pruneBoard(op, ap, rand, cardDefs, log, opPlan, plan);
    }
  }

  // fireTriggers() is NOT inert. It calls pruneBoard twice per board
  // entry, so merely CALLING it for an event no card carries still fires any
  // pending corpse's on_death EARLIER than before -- combat's own passives can
  // leave an entry at hp<=0 that used to survive until the next turn's step-0
  // prune. Measured: 8 of 9,828 whole-catalog matches changed, `turns`
  // included, with nothing but an unconditional fireTriggers call added.
  //
  // So the whole phase is skipped unless some entry actually prints the
  // trigger. That is what makes v4.32 a byte-identical no-op for every card
  // that exists today. A muted entry still passes this gate and is then
  // skipped inside fireTrigger -- deliberately, so the gate stays a pure
  // read of the printed card and cannot drift from isMuted().
  function boardHasTrigger(board, cardDefs, event) {
    for (const e of board) {
      const ts = cardDefs[e.card_id]?.effects_json?.triggers;
      if (!ts) continue;
      for (const t of ts) if (t && t.event === event) return true;
    }
    return false;
  }

  // ── v4.52 Sagas ───────────────────────────────────────────────────────────────
  // A Saga is a permanent with a { type: 'saga', chapters: N, loop_to: K }
  // passive. Its chapters are ordinary triggers with event 'chapter' and a
  // `chapter` number; several may share a number, and a number with none is a
  // rest chapter. Chapter I runs as it enters (fireTriggerOn above), then one
  // chapter at each of its owner's turn starts (step 4b). After chapter N it is
  // sacrificed, or with 1 <= K <= N the next chapter is K. ENGINE_SAGAS.md.
  //
  // entry.chapter is the last chapter that RAN (0 = not started). Created
  // lazily and never touched by the per-turn sweep, exactly like `counters`.

  // The printed saga passive (first wins), mute NOT consulted -- the step-4b
  // gate is a pure read of the card, the same rule boardHasTrigger follows.
  function sagaPassive(def) {
    const ps = def?.effects_json?.passives;
    if (!ps) return null;
    for (const p of ps) if (p && p.type === 'saga') return p;
    return null;
  }

  // Still in play, for real. `destroyed` alone is not enough: a bounce or a
  // damage-then-prune removes an entry without setting it, and the fake
  // { card_id } entry a Spell's on_enter fires through is never on a board.
  function sagaInPlay(entry, owner) {
    return !entry.destroyed && !(entry.hp !== null && entry.hp <= 0) && owner.board.includes(entry);
  }

  function romanNumeral(n) {
    const t = [[1000, 'M'], [900, 'CM'], [500, 'D'], [400, 'CD'], [100, 'C'], [90, 'XC'],
               [50, 'L'], [40, 'XL'], [10, 'X'], [9, 'IX'], [5, 'V'], [4, 'IV'], [1, 'I']];
    let s = '';
    for (const [v, r] of t) while (n >= v) { s += r; n -= v; }
    return s;
  }

  function advanceSaga(entry, ap, op, rand, cardDefs, log, plan, opPlan) {
    if (!sagaInPlay(entry, ap)) return;
    // Mute pauses a Saga where it stands: activePassives is the chokepoint.
    const ps = activePassives(entry, cardDefs);
    if (!ps) return;
    let sp = null;
    for (const p of ps) if (p && p.type === 'saga') { sp = p; break; }
    if (!sp) return;
    // Fails closed. The editor and api/card.php only save integers; anything
    // else would kill the whole card in both Go engines, so it does here too.
    const N = sp.chapters;
    if (!Number.isInteger(N) || N < 1) return;
    const L = sp.loop_to;
    const loopTo = (Number.isInteger(L) && L >= 1 && L <= N) ? L : 0;
    const cur = entry.chapter || 0;
    const next = cur < N ? cur + 1 : loopTo;
    if (!next) return;
    entry.chapter = next;

    const def = cardDefs[entry.card_id];
    const name = def?.name || entry.card_id;
    const prefix = (ap.pIndex === 0 || ap.pIndex === 1) ? `player${ap.pIndex + 1}: ` : '';
    if (log) {
      log.push(`§src:${entry.card_id}`); log.__src = entry.card_id;
      log.push(`  [chapter] ${prefix}chapter ${romanNumeral(next)} of ${name}`);
    }
    // The same per-trigger shape fireTriggerOn uses: one marker per trigger,
    // bare lines wrapped under this event, nested attributed lines left alone.
    for (const t of (def?.effects_json?.triggers || [])) {
      if (!t || t.event !== 'chapter' || t.chapter !== next) continue;
      if (log) { log.push(`§src:${entry.card_id}`); log.__src = entry.card_id; }
      const sub = (log && prefix) ? [] : log;
      if (sub !== log) sub.__src = log.__src;
      applyEffect(t.effect, ap, op, rand, cardDefs, sub, plan, undefined, entry, opPlan);
      if (sub !== log) {
        for (const l of sub) {
          const t2 = l.trim();
          log.push(t2.startsWith('§src:') || t2.startsWith('[on_') || t2.startsWith('[chapter]') ? l : `  [chapter] ${prefix}${t2}`);
        }
        log.__src = sub.__src;
      }
    }

    // The last chapter of a Saga that does not loop: sacrificed, by the same
    // sequence spend_counters_and's sacrifice_self uses. A chapter that has
    // already removed its own Saga leaves nothing to sacrifice.
    if (next === N && !loopTo && sagaInPlay(entry, ap)) {
      entry.destroyed = true;
      if (log) {
        log.push(`§src:${entry.card_id}`); log.__src = entry.card_id;
        log.push(`  [chapter] ${prefix}${name} has finished its last chapter`);
        log.push(`  [chapter] ${prefix}sacrificed ${name}`);
      }
      fireTriggerOn(entry, 'on_death', ap, op, rand, cardDefs, log, plan, opPlan);
      fireTriggerOn(entry, 'on_leave', ap, op, rand, cardDefs, log, plan, opPlan);
      ap.board = ap.board.filter(c => c !== entry);
      if (!championLeave(ap, entry.card_id, log, cardDefs))
        toDiscardOrExile(ap, entry, cardDefs, rand, log, op, plan, opPlan);
    }
  }

  // Step 4b. Only entries whose printed card is a Saga are visited, and only
  // they are followed by a prune: fireTriggers prunes after EVERY entry, and a
  // pass that pruned for non-Sagas would change games that have no Saga in
  // them (see boardHasTrigger above). opPlan is passed through on purpose --
  // fireTriggers drops it, and a chapter's discard_opponent picks with it.
  function fireSagas(entries, ap, op, rand, cardDefs, log, plan, opPlan) {
    for (const bc of entries) {
      if (!sagaPassive(cardDefs[bc.card_id])) continue;
      advanceSaga(bc, ap, op, rand, cardDefs, log, plan, opPlan);
      pruneBoard(ap, op, rand, cardDefs, log, plan, opPlan);
      pruneBoard(op, ap, rand, cardDefs, log, opPlan, plan);
    }
  }

  // ── Prune dead permanents ─────────────────────────────────────────────────────
  // Fires on_death + on_leave for each permanent whose hp ≤ 0.
  function pruneBoard(owner, opp, rand, cardDefs, log, plan, opPlan) {
    const dead = owner.board.filter(c => c.hp !== null && c.hp <= 0);
    for (const bc of dead) {
      if (log) log.push(`  ${cardDefs[bc.card_id]?.name || bc.card_id} died`);
      fireTriggerOn(bc, 'on_death', owner, opp, rand, cardDefs, log, plan, opPlan);
      fireTriggerOn(bc, 'on_leave', owner, opp, rand, cardDefs, log, plan, opPlan);
      if (!championLeave(owner, bc.card_id, log, cardDefs)) {
        toDiscardOrExile(owner, bc, cardDefs, rand, log, opp, plan, opPlan);
      }
    }
    owner.board = owner.board.filter(c => c.hp === null || c.hp > 0);
    return dead.length > 0;
  }

  // ── Battle plan: sort hand ────────────────────────────────────────────────────
  // A board entry's EFFECTIVE tags: its card definition's tags, plus a
  // synthetic 'token' for an entry that IS a token instance.
  //
  // A token copy keeps the card_id of whatever it copies, so a token made
  // from a non-token-rarity card carried that card's tags and nothing else --
  // 44 of the game's 131 create_token_copy effects copy a common, legendary,
  // infinite, uncommon, mythic or rare card (Straw Man-at-Arms, Ancient Power
  // Station, Fire Djinn, Death Knight...), and none of them were visible to
  // anything matching on 'token'. The engine already knew they were tokens --
  // entry.isToken is what makes them vanish on leave instead of going to the
  // discard -- so this just makes that knowledge visible to tag matching.
  //
  // Only meaningful for BOARD entries: a card in hand or deck has no token
  // instance to speak of, so the hand/deck/discard tag paths deliberately
  // keep reading the definition directly.
  function entryTags(entry, cardDefs) {
    const t = cardDefs[entry?.card_id]?.tags ?? [];
    return (entry && entry.isToken && !t.includes('token')) ? t.concat('token') : t;
  }
  // v4.68: a tag that names a grantable keyword ("flying", "reach", ...) also
  // matches a permanent that HAS that keyword right now -- printed or granted
  // (Divine Wind). Printed tag OR live keyword: nothing loses a match it had.
  // Every other tag is printed-only, and only a board entry can be granted
  // anything. Grant filters and `requires` gates stay printed-only (see
  // keywordGrantsFrom / countZoneByTag) so a grant can never feed itself.
  function entryHasTag(entry, tag, cardDefs) {
    if (entryTags(entry, cardDefs).includes(tag)) return true;
    return !!entry && GRANTABLE_KEYWORDS.has(tag) && entryHasKeyword(entry, tag, cardDefs);
  }
  // v4.68: exclude_tags / only_tags on a BOARD entry, through entryHasTag
  // (so a token copy also matches "token" -- Cave Elf).
  // hasExcludedTag / lacksRequiredTag stay for cards in a pile.
  function entryExcluded(entry, cardDefs, excludeTags) {
    return !!excludeTags?.length && excludeTags.some(t => entryHasTag(entry, t, cardDefs));
  }
  function entryLacksRequired(entry, cardDefs, onlyTags) {
    return !!onlyTags?.length && !onlyTags.some(t => entryHasTag(entry, t, cardDefs));
  }

  function hasTag(def, tag) {
    return !!def && (def.tags ?? []).includes(tag);
  }

  // The two baseline (Infinite-rarity, always-available-to-anyone) cards --
  // must stay in sync with inc/beginner_mode.php's BEGINNER_INFINITE_IDS,
  // the only other place these card ids are hardcoded. Kept as a fixed id
  // pair rather than checking rarity=='infinite' at runtime purely to match
  // that existing convention -- cardDefs here don't otherwise carry rarity
  // at all, so there's no meaningful difference in robustness either way.
  // v4.73: Brindeloid Warrior (811) added. It has been Infinite rarity (a 6-cost
  // vanilla 3/3/3 padding body) all along but was missing here, so under
  // costliest-first it played BEFORE a deck's real cards. Not every Infinite
  // card is filler: Shatter/Dispel/Raze/Cull (1541-1543, 1546, Infinite since
  // 2026-11-22) are real removal and sort like any other card -- this list is
  // the FILLER set, not "rarity = infinite".
  const INFINITE_CARD_IDS = new Set([1, 57, 811]);

  function sortHand(hand, cardDefs, plan, rand, board) {
    const mode = plan?.play_priority;
    if (mode === 'random') {
      const shuffled = shuffle(hand, rand);
      hand.length = 0;
      hand.push(...shuffled);
    } else if (mode === 'draw_order') {
      // No-op: hand is already in the order it was drawn, since every
      // insertion (tryDraw, mulligan, card-draw effects) appends via push
      // and removals (playing/discarding) never reorder what's left.
    } else if (mode === 'costliest') {
      hand.sort((a, b) => (cardDefs[b]?.cost ?? 0) - (cardDefs[a]?.cost ?? 0));
    } else if (mode === 'card_order' && Array.isArray(plan?.card_order)) {
      const orderMap = {};
      plan.card_order.forEach((id, i) => { orderMap[id] = i; });
      hand.sort((a, b) => {
        const pa = orderMap[a] ?? 9999, pb = orderMap[b] ?? 9999;
        if (pa !== pb) return pa - pb;
        return (cardDefs[a]?.cost ?? 99) - (cardDefs[b]?.cost ?? 99);
      });
    } else if (mode === 'type_order' && Array.isArray(plan?.type_order)) {
      const typeRank = {};
      plan.type_order.forEach((t, i) => { typeRank[t.toLowerCase()] = i; });
      hand.sort((a, b) => {
        const ra = typeRank[(cardDefs[a]?.supertype || '').toLowerCase()] ?? 9999;
        const rb = typeRank[(cardDefs[b]?.supertype || '').toLowerCase()] ?? 9999;
        if (ra !== rb) return ra - rb;
        return (cardDefs[a]?.cost ?? 99) - (cardDefs[b]?.cost ?? 99);
      });
    } else if (mode === 'tag_order' && Array.isArray(plan?.tag_order)) {
      // A card can carry several ability tags (unlike exactly one
      // supertype), so its rank is the BEST (lowest-index) rank among all
      // its tags that appear in tag_order -- found by walking tag_order
      // itself in priority order and taking the first tag (via hasTag())
      // the card actually has, rather than a single direct lookup like
      // type_order's.
      const rank = (cid) => {
        const def = cardDefs[cid];
        for (let i = 0; i < plan.tag_order.length; i++) {
          if (hasTag(def, plan.tag_order[i].toLowerCase())) return i;
        }
        return 9999;
      };
      hand.sort((a, b) => {
        const ra = rank(a), rb = rank(b);
        if (ra !== rb) return ra - rb;
        return (cardDefs[a]?.cost ?? 99) - (cardDefs[b]?.cost ?? 99);
      });
    } else {
      // default: cheapest first
      hand.sort((a, b) => (cardDefs[a]?.cost ?? 99) - (cardDefs[b]?.cost ?? 99));
    }
    // Baseline infinite-rarity cards are a safety-net padding mechanism, not
    // a real strategic pick -- being 0-cost, they sorted to the very FRONT
    // under the default "cheapest first" order, so a deck carrying any real
    // amount of them played a wall of it before a single genuine card
    // (reported: 57 infinite cards ahead of 43 real ones in one player's
    // deck). Stable-partition them to the back here, after whichever mode
    // above already decided the order among everything else -- this
    // overrides every play_order mode uniformly, not just cheapest-first --
    // EXCEPT card_order: id 1 (Ancient Power Station) is itself an
    // Infinite-rarity card but a genuinely useful one (grants ephemeral
    // energy each turn), and a player who explicitly ranks it in their own
    // card_order has made a deliberate choice this blanket demotion would
    // otherwise silently overrule every time (reported: a custom card_order
    // ranking Ancient Power Station above Volcanic Flow still played
    // Volcanic Flow first, 2026-08-03).
    // v4.69 battle_plan.prefer_new_names ("Collector", Fry Guy 2026-11-13):
    // after the play order above, a card whose NAME is already on your own
    // board drops behind every card whose name is not -- new and different
    // permanents first. A stable partition, so each group keeps the order
    // play_priority gave it; spells are never on the board, so never demoted.
    // Runs before the Infinite demotion below, which still has the last word.
    if (plan?.prefer_new_names === true && board && board.length) {
      const onBoard = new Set();
      for (const e of board) if (!e.destroyed && cardDefs[e.card_id]?.name) onBoard.add(cardDefs[e.card_id].name);
      if (onBoard.size) {
        const isDup = cid => onBoard.has(cardDefs[cid]?.name);
        const fresh = hand.filter(cid => !isDup(cid)), dupes = hand.filter(isDup);
        hand.length = 0;
        hand.push(...fresh, ...dupes);
      }
    }
    if (mode !== 'card_order') {
      const nonInfiniteCards = hand.filter(cid => !INFINITE_CARD_IDS.has(+cid));
      const infiniteCards    = hand.filter(cid => INFINITE_CARD_IDS.has(+cid));
      hand.length = 0;
      hand.push(...nonInfiniteCards, ...infiniteCards);
    }
  }

  // v4.69: the destroy_all / destroy_permanents_by_cost target filters, lifted
  // out of their effect cases UNCHANGED so removalWouldHit() asks exactly the
  // question the effect itself asks.
  function destroyAllMatches(c, eff, cardDefs) {
    const def = cardDefs[c.card_id] ?? {};
    // supertype filter
    const sts = eff.target_supertypes;
    if (sts && sts.length) {
      if (!sts.some(st => matchesSupertype(def, st))) return false;
    } else if (eff.target_supertype) {
      if (!matchesSupertype(def, eff.target_supertype)) return false;
    }
    // tag filter
    if (eff.tags && eff.tags.length) {
      if (!eff.tags.some(t => entryHasTag(c, t, cardDefs))) return false;
    } else if (eff.tag) {
      if (!entryHasTag(c, eff.tag, cardDefs)) return false;
    }
    // cost filter
    if (eff.cost_op) {
      const cost = def.cost ?? 0;
      const val  = eff.amount ?? 0;
      if (eff.cost_op === 'lt'  && !(cost <  val)) return false;
      if (eff.cost_op === 'lte' && !(cost <= val)) return false;
      if (eff.cost_op === 'gt'  && !(cost >  val)) return false;
      if (eff.cost_op === 'gte' && !(cost >= val)) return false;
      if (eff.cost_op === 'eq'  && cost !== val)   return false;
    }
    return true;
  }

  function destroyByCostMatches(c, eff, cardDefs) {
    const filter = eff.target_supertype || 'permanent';
    const costOp = eff.cost_op || 'lte';
    const thresh = eff.cost_threshold ?? 0;
    const def = cardDefs[c.card_id] ?? {};
    if (!matchesSupertype(def, filter)) return false;
    const cost = def.cost ?? 0;
    if (costOp === 'lt')  return cost <  thresh;
    if (costOp === 'lte') return cost <= thresh;
    if (costOp === 'gt')  return cost >  thresh;
    if (costOp === 'gte') return cost >= thresh;
    if (costOp === 'eq')  return cost === thresh;
    return false;
  }

  // ── v4.69 battle_plan.hold_removal_without_target ──────────────────────────
  // "Sadist" (Fry Guy, 2026-11-13): never waste a destroy card on a board with
  // nothing it could destroy. A card is REMOVAL when one of its on_enter
  // triggers' TOP-LEVEL effects is in the destroy family below (nested
  // then/else branches and other events are deliberately out of scope -- the
  // rule has to be predictable). It is HELD while every one of those effects
  // would find no ENEMY permanent it can actually destroy: each pool below is
  // the exact pool its effect case builds, and an entry that is indestructible
  // does not count, because destroying it does nothing. A board wipe that also
  // hits your own side still needs an enemy target (owner's call: "a fair
  // limitation/weakness").
  const REMOVAL_EFFECT_TYPES = new Set(['destroy_target', 'kill_target', 'destroy_random_tagged',
                                        'destroy_tagged', 'destroy_all', 'destroy_permanents_by_cost']);
  function removalEffectsOf(def) {
    const out = [];
    for (const t of def?.effects_json?.triggers ?? []) {
      if (t?.event === 'on_enter' && t.effect && REMOVAL_EFFECT_TYPES.has(t.effect.type)) out.push(t.effect);
    }
    return out;
  }
  function isRemovalCard(def) { return removalEffectsOf(def).length > 0; }
  function removalPool(eff, op, cardDefs) {
    const live = op.board.filter(c => !c.destroyed);
    switch (eff.type) {
      case 'destroy_target':
        return narrowByTypeAndCost(live.filter(c => !isUntargetable(c, cardDefs)), eff, cardDefs);
      case 'kill_target':
        return narrowByTypeAndCost(live.filter(c => c.hp !== null && !isUntargetable(c, cardDefs)), eff, cardDefs);
      case 'destroy_random_tagged': {
        const tag = eff.tag || '';
        return narrowByTypeAndCost(live.filter(c => !isUntargetable(c, cardDefs) && (!tag || entryHasTag(c, tag, cardDefs))), eff, cardDefs);
      }
      case 'destroy_tagged':
        return live.filter(c => entryHasTag(c, eff.tag || '', cardDefs));
      case 'destroy_all':
        return live.filter(c => destroyAllMatches(c, eff, cardDefs));
      case 'destroy_permanents_by_cost':
        return live.filter(c => destroyByCostMatches(c, eff, cardDefs));
    }
    return [];
  }
  // True when the card is not removal, or at least one of its removal effects
  // has a destructible enemy target right now.
  function removalWouldHit(def, op, cardDefs) {
    const effs = removalEffectsOf(def);
    if (!effs.length) return true;
    return effs.some(eff => removalPool(eff, op, cardDefs).some(c => !isIndestructible(c, cardDefs)));
  }
  // Hand-limit discard (D7): a plan that holds removal keeps it -- the discard
  // comes from the non-removal cards while there are any. Same pickDiscardFrom
  // rule among them. With the flag off, or a hand of only removal / no removal,
  // this IS pickDiscardIdx, so no existing replay moves.
  function pickHandLimitDiscard(hand, cardDefs, plan, rand) {
    if (plan?.hold_removal_without_target === true) {
      const keep = hand.map((cid, i) => isRemovalCard(cardDefs[cid]) ? -1 : i).filter(i => i >= 0);
      if (keep.length && keep.length < hand.length) return pickDiscardFrom(keep, hand, cardDefs, plan, rand);
    }
    return pickDiscardIdx(hand, cardDefs, plan, rand);
  }

  // ── Battle plan: which card leaves the hand ─────────────────────────────────
  // ONE rule for every discard a player makes: the 10-card hand limit,
  // discard_self, discard_and, an additional_cost.discard, and -- v4.06 -- a
  // discard FORCED BY THE OPPONENT, where it is the victim's own plan that
  // decides what they pitch. It used to be copied inline at three of those
  // sites and absent from the fourth.
  //
  // `candidates` is the list of hand indexes to choose among: an additional
  // cost excludes the card being played, every other caller passes the whole
  // hand. Returns -1 only when there is nothing to choose.
  function pickDiscardFrom(candidates, hand, cardDefs, plan, rand) {
    if (!candidates.length) return -1;
    // Gravecast-first is a FILTER applied BEFORE the ordering rule, not an
    // ordering of its own: a gravecast card can still be played from the
    // discard pile, so pitching it costs less than pitching anything else.
    // Narrowed only when the hand actually holds one, so the chosen ordering
    // still decides among them and still decides everything when it holds
    // none.
    if (plan?.discard_gravecast_first) {
      const gc = candidates.filter(i => hasCardPassive(cardDefs[hand[i]], 'gravecast'));
      if (gc.length) candidates = gc;
    }
    const prio = plan?.discard_priority;
    const cost = i => cardDefs[hand[i]]?.cost ?? 0;
    // First-wins on every tie, matching the loops these replaced.
    if (prio === 'cheapest')  return candidates.reduce((b, i) => cost(i) < cost(b) ? i : b, candidates[0]);
    if (prio === 'costliest') return candidates.reduce((b, i) => cost(i) > cost(b) ? i : b, candidates[0]);
    if (prio === 'lowest_priority') {
      // Same worst-rank-in-card_order rule as sortHand's own card_order lookup
      // (a card absent from card_order gets rank 9999, the same "not
      // explicitly prioritized" precedent) -- ties, including multiple
      // unranked cards, broken costliest-first.
      const orderMap = {};
      (plan?.card_order ?? []).forEach((id, i) => { orderMap[id] = i; });
      const rankOf = i => orderMap[hand[i]] ?? 9999;
      return candidates.reduce((b, i) => {
        const r = rankOf(i), rb = rankOf(b);
        return (r > rb || (r === rb && cost(i) > cost(b))) ? i : b;
      }, candidates[0]);
    }
    // One rand() draw over the candidate list -- identical to the old
    // `rand() * hand.length` whenever the list is the whole hand, which is
    // what keeps every existing replay byte-identical with the toggle off.
    return candidates[Math.floor(rand() * candidates.length)];
  }

  function pickDiscardIdx(hand, cardDefs, plan, rand) {
    return pickDiscardFrom(hand.map((_, i) => i), hand, cardDefs, plan, rand);
  }

  // ── Battle plan: narrow a targeting pool by tag priority ─────────────────────
  // battle_plan.target_tag_order is target_preference's tag-aware companion --
  // same "best (lowest-index) position wins" rule as tag_order (play_priority),
  // but since this feeds a single-target *selection* rather than a full sort,
  // "unmatched ranks last" becomes "unmatched is excluded": the pool is narrowed
  // down to just the best-ranked tier, then target_preference (weakest/
  // strongest/least_armor/random) breaks ties within that tier exactly as it
  // already does for the whole pool. Returns the pool untouched when
  // target_tag_order is unset/empty, or when nothing in the pool matches any
  // listed tag at all -- an empty result would make the effect silently
  // fizzle for no visible reason, so "no match anywhere" degrades to "ignore
  // the preference" rather than "hit nothing."
  function filterByTagOrder(pool, tagOrder, cardDefs) {
    if (!tagOrder || !tagOrder.length) return pool;
    const lowerOrder = tagOrder.map(t => String(t).toLowerCase());
    const rankOf = e => {
      const tags = entryTags(e, cardDefs).map(t => String(t).toLowerCase());
      // v4.68: live keywords rank like printed tags.
      for (const kw of GRANTABLE_KEYWORDS) if (!tags.includes(kw) && lowerOrder.includes(kw) && entryHasTag(e, kw, cardDefs)) tags.push(kw);
      let best = Infinity;
      for (let i = 0; i < lowerOrder.length; i++) {
        if (i < best && tags.includes(lowerOrder[i])) best = i;
      }
      return best;
    };
    const ranked    = pool.map(e => ({ e, r: rankOf(e) }));
    const bestRank  = Math.min(...ranked.map(x => x.r));
    if (!isFinite(bestRank)) return pool;
    return ranked.filter(x => x.r === bestRank).map(x => x.e);
  }

  // ── Battle plan: select a creature target from a pool ────────────────────────
  // v4.30: cardDefs is threaded in so a preference can read the card's COST,
  // which a board entry does not carry. Requested by Fry Guy 2026-10-15:
  // "selections like Highest cost/lowest cost/Highest melee/lowest melee/most
  // armor to go along with selecting the most/least of other stats."
  //
  // The three pre-existing values are untouched, because 29% of live decks
  // set one: weakest/strongest stay HEALTH (kkoechel's call), and least_armor
  // stays on curArmor. most_armor is its exact mirror rather than switching
  // to effectiveArmor, so the pair is coherent and no existing plan re-aims.
  // The melee pair uses effectiveMelee -- anthems and counters included --
  // because that is the number the player sees on the card, and there is no
  // older melee preference to stay compatible with.
  function selectTarget(pool, pref, rand, cardDefs) {
    if (!pool.length) return null;
    // Each entry maps to a comparable number, or null to sit the pick out.
    // A pool where NOTHING can be compared falls back to random, exactly as
    // the health branch below has always done for statless permanents.
    const BY = {
      lowest_melee:  e => effectiveMelee(e, e.owner?.board ?? pool, cardDefs),
      highest_melee: e => effectiveMelee(e, e.owner?.board ?? pool, cardDefs),
      most_armor:    e => e.curArmor ?? 0,
      // A missing DEFINITION sits the entry out; a definition with no cost
      // counts as 0. Go returns ok=false only for a nil def and Python only
      // for a None def -- matching that exactly is what keeps the three
      // engines picking the same entry when a pool is mixed.
      lowest_cost:   e => cardDefs?.[e.card_id] ? (cardDefs[e.card_id].cost ?? 0) : null,
      highest_cost:  e => cardDefs?.[e.card_id] ? (cardDefs[e.card_id].cost ?? 0) : null,
      // v4.51. Fryguy's pair, and the only axis here that is not a STAT:
      // every other preference reads a number off the card, while these read
      // WHEN the permanent arrived. iid is the per-match arrival counter, so
      // oldest is the lowest and newest the highest.
      //
      // Two consequences worth knowing. A permanent that is bounced and
      // replayed gets a FRESH entry and therefore a fresh iid, so it is newest
      // again -- correct, since from the board's point of view it did just
      // arrive. And iid is unique per entry, so unlike every other axis here
      // there are no ties to break.
      //
      // Only meaningful on a BOARD pool. selectPileCard() picks from a discard
      // or library by card id, where nothing ever "arrived", so these fall
      // through to its default there rather than inventing an order.
      oldest:        e => e.iid ?? null,
      newest:        e => e.iid ?? null,
    };
    if (BY[pref]) {
      const desc = pref === 'highest_melee' || pref === 'most_armor' || pref === 'highest_cost' || pref === 'newest';
      const scored = pool.map(e => [e, BY[pref](e)]).filter(([, v]) => v !== null && v !== undefined);
      if (scored.length) {
        // Sort toward the wanted end and take the first, so ties resolve to
        // whichever entry came first in the pool -- the same rule the health
        // branch and Go/Python's linear scans use.
        scored.sort((a, b) => desc ? b[1] - a[1] : a[1] - b[1]);
        return scored[0][0];
      }
      return pool[Math.floor(rand() * pool.length)];
    }
    if (pref === 'weakest' || pref === 'strongest') {
      // Permanents with no health stat (Structures/Relics/Enchantments) have
      // hp === null -- exclude them from the comparison rather than letting
      // them default to some sentinel, since a sentinel biases the pick one
      // way or the other depending on what value is chosen. Only fall back
      // to random if NOTHING in the pool has a health stat to compare.
      const withHp = pool.filter(e => e.hp !== null && e.hp !== undefined);
      if (withHp.length) {
        return pref === 'weakest'
          ? withHp.slice().sort((a, b) => a.hp - b.hp)[0]
          : withHp.slice().sort((a, b) => b.hp - a.hp)[0];
      }
      return pool[Math.floor(rand() * pool.length)];
    }
    if (pref === 'least_armor') return pool.slice().sort((a, b) => (a.curArmor ?? 0) - (b.curArmor ?? 0))[0];
    return pool[Math.floor(rand() * pool.length)];
  }

  // Card-level "highest cost" targeting toggle (eff.target_highest_cost) --
  // unlike target_preference (a per-player battle-plan setting applying
  // uniformly to all of that player's targeting effects), this is baked into
  // the specific card's effect and always wins regardless of the acting
  // player's plan. Only random among ties at the max cost, never a fixed
  // tie-break (e.g. array order), so two same-cost permanents are truly a
  // coinflip rather than one silently always winning.
  function selectHighestCostTarget(pool, cardDefs, rand) {
    if (!pool.length) return null;
    const maxCost = Math.max(...pool.map(e => cardDefs[e.card_id]?.cost ?? 0));
    const atMax = pool.filter(e => (cardDefs[e.card_id]?.cost ?? 0) === maxCost);
    return atMax[Math.floor(rand() * atMax.length)];
  }

  // Wraps selectTarget for utilize costs specifically (not sacrifice, and not
  // damage/kill/destroy targeting -- those keep plain selectTarget via
  // target_preference, untouched). Only changes the unset/'random' default:
  // a mixed or creature-only utilize pool now prefers the lowest-melee
  // creature over a true random pick (kkoechel: "prioritize lower melee
  // creatures for utilization of creatures, ... utilizing relics,
  // structures and enchantments can remain random by default"). An explicit
  // cost_preference from the battle plan (weakest/strongest/least_armor)
  // still always wins -- this only reshapes what "no preference set" means
  // for creatures specifically; non-creature utilize pools (relic/
  // structure/enchantment) are untouched, still a plain random pick.
  function selectUtilizeTarget(pool, pref, rand, cardDefs) {
    if (!pool.length) return null;
    if (pref && pref !== 'random') return selectTarget(pool, pref, rand, cardDefs);
    const creatures = pool.filter(e => matchesSupertype(cardDefs[e.card_id], 'creature'));
    if (!creatures.length) return pool[Math.floor(rand() * pool.length)];
    return creatures.slice().sort((a, b) => (a.melee ?? 0) - (b.melee ?? 0))[0];
  }

  // ── Battle plan: select a card id from a raw discard-pile pool ───────────────
  // Unlike selectTarget (boardEntry objects with hp/curArmor), discard-pile
  // pools are flat card-id ints with no combat stats -- weakest/strongest are
  // reinterpreted as cheapest/costliest by mana cost. least_armor has no
  // meaningful analogue for a bare card id and falls back to random.
  function selectPileCard(pool, pref, cardDefs, rand) {
    if (!pool.length) return null;
    // v4.30 axes on a pile. A pile holds bare card ids with no live state, so
    // these read the PRINTED stats from the definition -- there is no current
    // armor or anthem to apply. Without this a plan set to highest_melee would
    // silently fall back to random for discard/library picks while steering
    // every board pick, which is worse than not offering the option.
    // Same rule as the board selector above: no definition sits the card out,
    // a definition with no such stat counts as 0. A pile is full of spells
    // with no melee or armor at all, so this is the common case, not an edge.
    const pstat = (cid, k) => cardDefs[cid] ? (cardDefs[cid].stats_json?.[k] ?? 0) : null;
    const PBY = {
      lowest_cost:   cid => cardDefs[cid] ? (cardDefs[cid].cost ?? 0) : null,
      highest_cost:  cid => cardDefs[cid] ? (cardDefs[cid].cost ?? 0) : null,
      lowest_melee:  cid => pstat(cid, 'melee'),
      highest_melee: cid => pstat(cid, 'melee'),
      least_armor:   cid => pstat(cid, 'armor'),
      most_armor:    cid => pstat(cid, 'armor'),
    };
    if (PBY[pref]) {
      const desc = pref === 'highest_cost' || pref === 'highest_melee' || pref === 'most_armor';
      const scored = pool.map(cid => [cid, PBY[pref](cid)]).filter(([, v]) => v !== null && v !== undefined);
      if (scored.length) {
        scored.sort((a, b) => desc ? b[1] - a[1] : a[1] - b[1]);
        return scored[0][0];
      }
      return pool[Math.floor(rand() * pool.length)];
    }
    if (pref === 'weakest' || pref === 'strongest') {
      // Sort toward the wanted end and take the first element -- under a
      // stable sort this keeps ties resolving to whichever card appeared
      // first in the pile, matching Go/Python's linear max()/min() scan
      // (sorting the OTHER direction and taking the last element would
      // silently flip tie-breaks to the last-occurring card instead).
      const sorted = pref === 'weakest'
        ? pool.slice().sort((a, b) => (cardDefs[a]?.cost ?? 0) - (cardDefs[b]?.cost ?? 0))
        : pool.slice().sort((a, b) => (cardDefs[b]?.cost ?? 0) - (cardDefs[a]?.cost ?? 0));
      return sorted[0];
    }
    return pool[Math.floor(rand() * pool.length)];
  }

  // ── Play one card; return true if a card was played ───────────────────────────
  // energy_hold_scope ('every_turn' default | 'first_play' | 'first_spend'):
  // 'every_turn'/'first_play' are a single upfront gate before the hand scan
  // even starts (first_play just adds "...unless already played something").
  // 'first_spend' is different in kind -- free cards must always be allowed
  // through regardless of the hold, so it can't be a single blanket gate; it's
  // checked per-candidate-card inside the scan loop instead (below), skipping
  // just the specific card that would cost energy rather than the whole turn.
  // v4.24: a card may print its own cost for being played FROM THE DISCARD --
  // {"type":"gravecast","cost":N}. Returns null when unset, which is the
  // overwhelmingly common case and means "use the printed cost".
  //
  // The key is `cost`, NOT `amount`, for two reasons. Go decodes a missing
  // `amount` as a literal 0 (see passiveDef.Sweep, which became a string for
  // exactly this reason), and 0 has to mean a FREE gravecast here. And
  // `amount` already carries an "amount=0 means all" convention on other
  // effects. A dedicated key is unambiguous in all four engines.
  //
  // 0 IS A VALID COST (a free gravecast), so this tests for the key's PRESENCE
  // and never for truthiness -- `amount || def.cost` would silently turn every
  // free gravecast back into a full-price one.
  //
  // A free gravecast is NOT free recursion: gravecast exiles what it plays (a
  // spell outright, a permanent as a token copy with the original exiled), so
  // the card leaves the discard after one use. Cost 0 buys one extra casting,
  // not a loop -- kkoechel, 2026-10-13.
  function gravecastCost(def) {
    const ps = def?.effects_json?.passives;
    if (!ps) return null;
    for (const p of ps) {
      if (p.type !== 'gravecast') continue;
      const a = p.cost;
      if (typeof a === 'number' && Number.isFinite(a) && a >= 0) return a;
      return null;
    }
    return null;
  }

  // `baseOverride` replaces the card's PRINTED cost, not the final one, so a
  // Gravecast 2 card is still discounted by a cost_modifier permanent and
  // still pays Chaos Arena's supertype tax. Making it replace the final cost
  // would quietly make the card immune to every modifier in the game.
  function effectiveCost(def, ap, op, cardDefs, chaosCounts, baseOverride) {
    const costMod = costModifierDelta(def, ap, op, cardDefs);
    const dynMod  = dynamicCostDelta(def, ap, op, cardDefs);
    const base    = (typeof baseOverride === 'number') ? baseOverride : (def.cost ?? 0);
    let effCost = Math.max(costMod.minimum, dynMod.minimum, base + costMod.delta + dynMod.delta);
    // Chaos Arena supertype taxes -- applied after the discount floor so a
    // tax always fully lands regardless of other cost reductions.
    if (matchesSupertype(def, 'spell'))       effCost += chaosCounts?.spell_tax || 0;
    if (matchesSupertype(def, 'creature'))    effCost += chaosCounts?.creature_tax || 0;
    if (matchesSupertype(def, 'relic'))       effCost += chaosCounts?.relic_tax || 0;
    if (matchesSupertype(def, 'enchantment')) effCost += chaosCounts?.enchantment_tax || 0;
    return effCost;
  }

  function playOneCard(ap, op, cardDefs, rand, plan, opPlan, iid, log, chaosCounts) {
    const hold  = plan?.energy_hold ?? 0;
    const scope = plan?.energy_hold_scope ?? 'every_turn';
    if (hold > 0 && scope !== 'first_spend') {
      const gateActive = scope !== 'first_play' || !ap.hasPlayedFirstCard;
      if (gateActive && (ap.energy + ap.ephemeral) < hold) return false;
    }

    // card_order priority is otherwise "play whatever's affordable, in
    // priority order" -- with no hold of its own, a player's #1 pick just
    // sits in hand forever once cheaper cards use up its would-be energy
    // every turn, since nothing ever waits for it (confirmed live: Battle
    // Ogre at cost 4 never got played across 20 turns while cost 1-3 cards
    // kept getting played instead, reported by a player as "custom card
    // order isn't working right", 2026-08-03). So: reserve BASIC energy
    // for card_order[0] specifically -- the single literal top entry, NOT
    // "whichever card_order-ranked card currently in hand ranks highest."
    // The latter was tried first and shipped as v3.55, but it cascades
    // badly on any real deck with several ranked-but-not-#1 expensive
    // cards: whichever one happens to be in hand becomes the hold target,
    // and the moment it's played (or a costlier one is drawn), a NEW
    // card takes over as "best in hand" and re-triggers the hold --
    // chaining indefinitely and blocking everything below it in the list,
    // including cheap/ephemeral-source cards that were never meant to be
    // held for at all (confirmed live: a real deck's energy climbed to
    // 10+ over 11 turns with NOTHING played, because 5+ different
    // mid-priority cards costing 4-13 kept taking turns re-triggering the
    // hold, permanently starving a cost-3 card ranked below all of them;
    // reported as "my deck is horrible now, APS doesn't get played at
    // all", 2026-08-03). Scoping to card_order[0] only means at most ONE
    // card can ever create a hold, matching the original ask ("protect my
    // #1 pick") without the unbounded chain. Ephemeral energy is exempt
    // from the reservation either way -- it clears at end of turn
    // regardless of whether it's spent, so holding it back would just
    // waste it rather than protect anything; only cards payable using
    // ephemeral alone stay playable during a hold (checked per-candidate
    // below). `card_order_hold: false` opts a plan out of the reservation
    // entirely, restoring the plain greedy-affordable behavior.
    let cardOrderHoldActive = false;
    if (plan?.play_priority === 'card_order' && Array.isArray(plan?.card_order) && plan.card_order.length && plan?.card_order_hold !== false) {
      // Compared as strings: a saved plan stores card ids as numbers, but the
      // browser simulators (deck-gallery's Challenge / vs 100 Strawmen) build
      // their decks as id strings, so includes() never matched and the hold
      // silently never fired -- a 2-cost top card behind 1-cost fillers was
      // drawn and then never cast (Fry Guy, 2026-11-04: "always decks").
      // Arena payloads are ints on both sides, so their behaviour is unchanged.
      const topCid = ap.hand.find(c => String(c) === String(plan.card_order[0]));
      if (topCid !== undefined) {
        const topDef = cardDefs[topCid];
        if (topDef && (ap.energy + ephemeralFor(ap, topDef)) < effectiveCost(topDef, ap, op, cardDefs, chaosCounts)) cardOrderHoldActive = true;   // v4.64 Patient
      }
    }

    // ── Gravecast ────────────────────────────────────────────────────────────
    // A card with the `gravecast` passive may be played straight out of the
    // DISCARD pile. This is the first mechanic in the game where a card is
    // played from anywhere but the hand, so the candidate list below is a
    // merge of the two zones rather than a scan of ap.hand.
    //
    // INTERLEAVED, deliberately: the merged list is handed to the SAME
    // sortHand() the hand alone used to get, so a battle plan's play_priority
    // ranks hand and discard candidates against each other instead of the
    // discard being a second-class afterthought. Two consequences worth
    // knowing: 'draw_order' sees hand first then discard (the concatenation
    // order), and under 'random' the shuffle covers both zones at once.
    //
    // When nothing in the discard is eligible, `combined` has exactly the same
    // contents and length as ap.hand did, so sortHand consumes an identical
    // amount of RNG and every existing deck plays exactly as before.
    //
    // The CHAMPION is never eligible. A champion card is redirected to its own
    // zone when it leaves the board (see toDiscardOrExile's caller), so letting
    // it be gravecast out of the discard would fight that bookkeeping for no
    // design gain -- kkoechel's call: "remove gravecast from any champion card
    // before the game starts".
    const gcEligible = [];
    for (const dcid of ap.discard) {
      if (dcid === ap.championId) continue;
      if (hasCardPassive(cardDefs[dcid], 'gravecast')) gcEligible.push(dcid);
    }
    // battle_plan.gravecast_policy (v4.05) decides HOW the discard candidates
    // meet the hand ones. Absent/unknown behaves as 'interleaved', which is
    // what v4.04 shipped, so an untouched plan plays identically.
    //
    //   interleaved   both zones sorted TOGETHER by play_priority (default)
    //   hand_first    every hand card gets first refusal; the discard is only
    //                 reached when nothing in hand is playable (no cards left,
    //                 or all of them unaffordable)
    //   discard_first the mirror -- cash the discard in before your own
    //                 recycling shuffles it away
    //   never         don't gravecast at all; keeps the pile intact for
    //                 recycle_self / shuffle_discard_to_library, which
    //                 gravecast would otherwise EXILE permanently
    const gcPolicy = plan?.gravecast_policy || 'interleaved';
    if (gcPolicy === 'never') gcEligible.length = 0;

    // sortHand sorts ap.hand IN PLACE and that side effect is load-bearing:
    // fulfillDiscardCost and the end-of-turn discard pick by hand POSITION, so
    // handing them an unsorted hand changes which cards get discarded. Sorting
    // a copy instead silently altered 7,976 of 30,912 catalog-sweep matches
    // (26%) on the first cut of this patch, with no card carrying the keyword.
    // So: always sort the hand exactly as before, and only when something is
    // actually gravecastable build the merged candidate list on top of it.
    // With an empty discard side `combined` IS ap.hand and `zones` is null, so
    // the no-gravecast path is the original code, instruction for instruction.
    sortHand(ap.hand, cardDefs, plan, rand, ap.board);
    let combined = ap.hand;
    let zones = null;                       // zones[i] === true -> discard copy
    if (gcEligible.length) {
      if (gcPolicy === 'interleaved') {
        combined = ap.hand.concat(gcEligible);
        sortHand(combined, cardDefs, plan, rand, ap.board);
        // Sorting mixes the zones, so a candidate's zone cannot be read off
        // its index -- derive it by consuming the hand copies first. A card
        // sitting in BOTH zones therefore spends its hand copies first, which
        // is strictly better for the player (the ordinary copy goes, the
        // recursion stays available).
        const handLeft = new Map();
        for (const c of ap.hand) handLeft.set(c, (handLeft.get(c) || 0) + 1);
        zones = combined.map(cid => {
          const n = handLeft.get(cid) || 0;
          if (n > 0) { handLeft.set(cid, n - 1); return false; }
          return true;
        });
      } else {
        // The two zones stay contiguous, so position IS the zone. Each group
        // is sorted by play_priority within itself. The loop plays the first
        // AFFORDABLE candidate, which is what makes hand_first mean "only
        // when nothing in hand can be played" without extra control flow.
        sortHand(gcEligible, cardDefs, plan, rand, ap.board);
        if (gcPolicy === 'discard_first') {
          combined = gcEligible.concat(ap.hand);
          zones = combined.map((_, i) => i < gcEligible.length);
        } else {
          combined = ap.hand.concat(gcEligible);
          zones = combined.map((_, i) => i >= ap.hand.length);
        }
      }
    }

    for (let ci = 0; ci < combined.length; ci++) {
      const cid = combined[ci];
      const def = cardDefs[cid];
      if (!def) continue;
      const fromDiscard = zones ? zones[ci] : false;
      let i = fromDiscard ? -1 : ap.hand.indexOf(cid);
      if (!fromDiscard && i < 0) continue;
      const effCost = effectiveCost(def, ap, op, cardDefs, chaosCounts,
                                    fromDiscard ? gravecastCost(def) ?? undefined : undefined);
      if (cardOrderHoldActive && effCost > ephemeralFor(ap, def)) continue;   // v4.64: a Patient card cannot ride Temp Energy past the hold
      // first_spend: free cards always pass; a card that would actually cost
      // energy is held (skip to the next hand card, don't abort the turn)
      // until the first paid play has ever happened.
      if (hold > 0 && scope === 'first_spend' && effCost > 0 &&
          !ap.hasSpentFirstEnergy && (ap.energy + ap.ephemeral) < hold) continue;
      // A gravecast card leaves the discard BEFORE its costs are tested, so it
      // can never pay for itself: an additional_cost of "recycle 2 from
      // discard" or "exile 2 from discard" sees a pile that no longer contains
      // it, and a spell whose own effect shuffles the discard back into the
      // deck cannot put itself back either. Restored untouched if the play
      // turns out to be unaffordable.
      let gcIdx = -1;
      if (fromDiscard) {
        gcIdx = ap.discard.lastIndexOf(cid);
        if (gcIdx < 0) continue;
        ap.discard.splice(gcIdx, 1);
      }
      // v4.40. Was one boolean chain ending in a bare `continue`, which meant
      // NINE different gates failed silently -- Caution to the Wind sits in
      // hand all game at 6 shields and the replay says nothing about it. This
      // is an autobattler: the player is not there to notice, so the replay is
      // the only channel that can explain it.
      //
      // Same short-circuit order and the same predicates, so behaviour is
      // unchanged; the only addition is a log line, and only when:
      //   * the card was OTHERWISE playable -- a plain "can't afford it" is
      //     obvious from the energy and would fire on half the hand every turn
      //   * this card has not already been explained this turn, because the
      //     hand is rescanned after every single play
      const affordable = canAfford(ap, effCost, def);
      let blockedBy = null;
      if (affordable) {
        // v4.41: the To Play gate goes FIRST because it is what the card
        // prints first, so when several things block at once the replay
        // reports the one the player reads at the top of the card.
        // ONE call, not a predicate plus a message-builder: two functions
        // walking the same list could disagree, and a disagreement here plays
        // a card whose gate is unmet (blockedBy null => the play proceeds).
        const gate = playGateBlocker(def, ap, op, cardDefs);
        if      (gate)                                           blockedBy = gate;
        else if (!canFulfillUtilizeCost(ap, cardDefs, def))      blockedBy = 'needs a permanent to utilize';
        else if (!canFulfillSacrificeCost(ap, cardDefs, def))    blockedBy = 'needs a permanent to sacrifice';
        else if (!canFulfillHonorCost(ap, def))                  blockedBy = `needs ${def.effects_json.additional_cost.honor} honor (has ${ap.honor})`;
        else if (!canFulfillDiscardCost(ap, def))                blockedBy = 'needs cards in hand to discard';
        else if (!canFulfillLifeCost(ap, def))                   blockedBy = `needs ${def.effects_json.additional_cost.life} life (has ${ap.life})`;
        else if (!canFulfillShieldsCost(ap, def))                blockedBy = `needs ${def.effects_json.additional_cost.shields} shields (has ${ap.shields})`;
        else if (!canFulfillRecycleCost(ap, def))                blockedBy = 'needs cards in the discard to recycle';
        else if (!canFulfillExileDiscardCost(ap, def))           blockedBy = 'needs cards in the discard to exile';
        // v4.69: the player's own plan holds a destroy card with nothing to destroy.
        else if (plan?.hold_removal_without_target === true && !removalWouldHit(def, op, cardDefs)) blockedBy = 'held: nothing to destroy';
      }
      if (!affordable || blockedBy) {
        if (log && blockedBy && ap.blockedLogged && !ap.blockedLogged.has(cid)) {
          ap.blockedLogged.add(cid);
          log.push(`  could not play ${def?.name || cid}: ${blockedBy}`);
        }
        if (fromDiscard) ap.discard.splice(gcIdx, 0, cid);
        continue;
      }

      spendEnergy(ap, effCost, def);
      tally(ap, 'cards_played', 1);
      // Gravecast: playing off the discard exiles the original (a permanent
      // enters as a token copy, a spell is exiled outright). Either way a card
      // left the game, which is what cards_exiled counts.
      if (fromDiscard) tally(ap, 'cards_exiled', 1);
      ap.hasPlayedFirstCard = true;
      if (effCost > 0) ap.hasSpentFirstEnergy = true;
      // Every cost line is collected here and printed right AFTER "played X"
      // below. Logged in payment order they came first, so a reader took each
      // block for the card ABOVE it -- the last card's costs looked unpaid
      // (Fry Guy, 2026-10-28). Log order only; payment order is unchanged.
      const costLog = log ? [] : null;
      if (def?.effects_json?.additional_cost?.utilize) fulfillUtilizeCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
      if (def?.effects_json?.additional_cost?.sacrifice) fulfillSacrificeCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
      if (def?.effects_json?.additional_cost?.honor) fulfillHonorCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
      if (def?.effects_json?.additional_cost?.discard) i = fulfillDiscardCost(ap, op, cardDefs, def, i, rand, costLog, plan, opPlan);
      if (def?.effects_json?.additional_cost?.life) fulfillLifeCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
      if (def?.effects_json?.additional_cost?.shields) fulfillShieldsCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
      if (def?.effects_json?.additional_cost?.recycle) fulfillRecycleCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
      if (def?.effects_json?.additional_cost?.exile_discard) fulfillExileDiscardCost(ap, op, cardDefs, def, rand, costLog, plan, opPlan);
      // v4.48: the OPTIONAL extra, paid last -- after the base cost and every
      // mandatory cost, so it can never affect whether this card was playable.
      // A card with no supplement takes a path that consumes zero RNG, which
      // is what keeps the existing catalogue replaying bit-identically.
      const supDeferred = canPaySupplement(ap, def, cardDefs, plan) ? paySupplement(ap, op, cardDefs, def, rand, costLog, plan, opPlan) : null;
      // Re-locate the played card by identity rather than trusting the
      // stale index i -- unlike fulfillDiscardCost (which explicitly
      // tracks and adjusts the played card's own index as it discards
      // OTHER hand cards), every other addCost fulfiller above can carry
      // an arbitrary nested effect (utilize/sacrifice/honor/life/shields/
      // recycle/exile_discard cost + effect) that may itself draw/
      // discard/mill the same player's hand, shifting or removing the
      // slot i pointed at. Previously this silently no-opped (splice on
      // an out-of-range index does nothing), leaving the "played" card
      // stuck in hand forever -- the Go engine's equivalent hits a hard
      // slice-bounds panic on the same scenario (a Life-cost card whose
      // effect discarded from hand, confirmed live in production
      // 2026-08-03). Fast path (i still valid and still holds cid) is
      // unchanged; otherwise search for the nearest remaining copy. If
      // none remains, the card's own cost effect already removed it --
      // nothing left to do.
      if (!fromDiscard) {
        let removeIdx = i;
        if (removeIdx >= ap.hand.length || ap.hand[removeIdx] !== cid) {
          removeIdx = ap.hand.indexOf(cid);
        }
        if (removeIdx >= 0) ap.hand.splice(removeIdx, 1);
      }

      // effCost is what was actually charged (spendEnergy above already used
      // it) -- log that, not def.cost, or a cost_modifier/cost_scaling
      // discount or tax silently never showed up in the log/visualizer at
      // all despite correctly affecting real gameplay. costNote keeps the
      // card's own printed cost visible too when it differs, same spirit as
      // the "(amplified)" suffix elsewhere.
      const costNote = effCost !== (def.cost ?? 0) ? ` (base ${def.cost})` : '';
      if (isPermanent(def)) {
        const entry = {
          owner:    ap,
          card_id:  cid,
          hp:       canBeDamaged(def) ? def.stats_json.health : null,
          armor:    def.stats_json?.armor ?? 0,
          curArmor: def.stats_json?.armor ?? 0,
          melee:    def.stats_json?.melee ?? 0,
          utilized: false,
          stunCounters: 0,
          stunned:  false,
          // v4.22 Mute. muteCounters = how many of the owner's FUTURE turn-starts this
          // entry stays blank for; mutePermanent = Mute 0, blank until it leaves play.
          // Separate fields because Mute 0 is the PERMANENT case (unlike Stun 0, which
          // is the shortest one), so `muteCounters > 0` cannot answer "is it muted" --
          // isMuted() is the only thing that may.
          muted: false,
          muteCounters: 0,
          mutePermanent: false,
          loopCounts: {},
          iid:      ++iid.val,
          // Gravecast: the card itself is exiled (it was spliced out of the
          // discard above and is returned nowhere) and what enters play is a
          // TOKEN copy. isToken is what makes it vanish instead of landing in
          // the discard when it leaves -- without that the permanent would
          // recur every single turn, for its cost, forever.
          isToken:  fromDiscard
        };
        let chaosNote = '';
        const armorN = chaosCounts?.permanents_enter_armored || 0;
        if (armorN > 0) { entry.armor += armorN; entry.curArmor += armorN; chaosNote = ` (chaos: +${armorN} armor)`; }
        const meleeN = chaosCounts?.creatures_enter_melee_boosted || 0;
        if (meleeN > 0 &&
            (def.supertype || '').toLowerCase().includes('creature') && !hasCardPassive(def, 'melee_locked')) { entry.melee += meleeN; chaosNote = ` (chaos: +${meleeN} melee)`; }
        entry.curArmor += anthemBonus(cid, ap.board, cardDefs, 'anthem_armor', null);
        ap.board.push(entry);
        if (log) log.push(`  played ${def.name || cid} [${def.supertype || 'permanent'}] cost ${effCost}${costNote}${chaosNote}${fromDiscard ? ' (gravecast — token copy, original exiled)' : ''}`);
        if (costLog) log.push(...costLog);
        if (supDeferred) applyEffect(supDeferred, ap, op, rand, cardDefs, log, plan, undefined, entry, opPlan);   // v4.54
        fireTriggerOn(entry, 'on_enter', ap, op, rand, cardDefs, log, plan, opPlan);
        fireTagWatchers(ap, op, ap, entry, 'on_tag_played', rand, cardDefs, log, plan, opPlan);
        fireTagWatchers(op, ap, ap, entry, 'on_opponent_tag_played', rand, cardDefs, log, opPlan, plan);
      } else {
        if (log) log.push(`  played ${def.name || cid} [${def.supertype || 'spell'}] cost ${effCost}${costNote}${fromDiscard ? ' (gravecast — exiled)' : ''}`);
        if (costLog) log.push(...costLog);
        fireTriggerOn({ card_id: cid }, 'on_enter', ap, op, rand, cardDefs, log, plan, opPlan);
        fireTagWatchers(ap, op, ap, { card_id: cid }, 'on_tag_played', rand, cardDefs, log, plan, opPlan);
        fireTagWatchers(op, ap, ap, { card_id: cid }, 'on_opponent_tag_played', rand, cardDefs, log, opPlan, plan);
        // Gravecast exiles the spell instead of returning it to the discard --
        // otherwise it would be replayable from the discard every turn.
        if (!fromDiscard) arriveInDiscard(ap, cid, cardDefs, rand, log, 'spell', op, plan, opPlan);
      }
      return true;
    }
    return false;
  }

  // ── Single-game simulation (firstPlayer: 0=A goes first, 1=B goes first) ─────
  function _simSingleGame(cardsA, cardsB, cardDefs, seed, planA, planB, firstPlayer, championA, championB, chaosEffect, startingLife, honorToWin, startA, startB) {
    const rand  = RNG(seed);
    const deckA = shuffle(cardsA, rand);
    const deckB = shuffle(cardsB, rand);
    const first = firstPlayer;
    const p     = [
      mkPlayer(deckA, championA ?? null, resolveStartingLife(startA, startingLife), resolveStartingHonor(startA), resolveStartingShields(startA)),
      mkPlayer(deckB, championB ?? null, resolveStartingLife(startB, startingLife), resolveStartingHonor(startB), resolveStartingShields(startB)),
    ];
    // Back-pointers so a board entry can reach the OTHER board without every
    // caller threading both players -- see entryHasKeyword / entry.owner.
    p[0].opponent = p[1]; p[1].opponent = p[0];
    // Stable identity for pruneBoard's on_death/on_leave log-attribution fix
    // (shared helper, used by both _simSingleGame and recordBattle) -- see
    // that function's comment.
    p[0].pIndex = 0;
    p[1].pIndex = 1;
    const plans = [planA || null, planB || null];
    __iid.val = 0;
    const iid = __iid;
    const chaos = chaosEffect || null;
    const chaosCounts = parseChaosEffects(chaos);
    const ht    = honorToWin ?? ((chaosCounts.honor_threshold_50 > 0) ? 50 : HONOR_TO_WIN);

    // Opening hands: first player draws 5, second draws 6
    const draws = first === 0 ? [5, 6] : [6, 5];
    draws[0] += resolveStartingExtraCards(startA);
    draws[1] += resolveStartingExtraCards(startB);
    for (let i = 0; i < 2; i++)
      for (let j = 0; j < draws[i]; j++) tryDraw(p[i]);

    let active = first;

    for (let turn = 0; turn < MAX_TURNS; turn++) {
      const ai = active, oi = 1 - active;
      const ap = p[ai], op = p[oi];
      const plan = plans[ai];
      // Snapshot-delta for this turn's damage/poison achievement tracking
      // (Overwhelming Force / Venomous, game/player.php) -- deliberately
      // measures the net life/poison change on the opponent across the whole
      // turn rather than instrumenting every individual damage/poison
      // mutation site (combat phase, deal_damage_to_player, poison_opponent,
      // etc.), so it can't miss a code path.
      const turnLifeStart   = op.life;
      const turnPoisonStart = op.poison;
      // v4.42: zero BOTH players' per-turn counters, not just the active
      // player's. "This turn" has to mean the same window on both sides or an
      // opponent-side gate reads a stale number: with a per-player reset the
      // inactive player's tally would still hold THEIR last turn's events plus
      // whatever happened to them during this one -- two turns mixed together,
      // which is not what "your opponent gained 3 life this turn" says.
      //
      // BOTH turn loops need this. The fast loop is where submitted results
      // come from and the logged loop is the replay, so a reset in only one
      // makes the browser and the validators disagree (the v4.40
      // ephemeral_persists lesson).
      ap.turnTally = {};
      op.turnTally = {};


      // 0. Refresh utilized permanents; then shield decay + poison damage
      // v4.19 Stun: a stunned permanent spends one counter here INSTEAD of
      // untapping, so Stun N costs it N untaps and Stun 0 simply untaps as
      // normal on this very pass (it was tapped for the opponent's turn, which
      // is the whole of Stun 0). loopCounts resets either way -- it is
      // unrelated bookkeeping and starving it would be a second, hidden effect.
      for (const e of ap.board) {
        if (e.stunCounters > 0) { e.stunCounters--; }
        else { e.utilized = false; e.stunned = false; }
        // v4.22 Mute ticks on the SAME schedule as stun, and in the same
        // shape: a counter is spent HERE INSTEAD of clearing the flag, so the
        // permanent is still muted for this turn's trigger phase (step 4,
        // which runs after this) and Mute N costs exactly N of the owner's
        // trigger phases -- whoever applied it and on whose turn. Decrementing
        // and letting isMuted() read the counter directly silences only N-1
        // turns, because the last decrement lands before the phase it was
        // meant to cover. mutePermanent (Mute 0) never ticks: it ends only
        // when the permanent leaves play, and the entry goes with it.
        if (!e.mutePermanent) {
          if (e.muteCounters > 0) { e.muteCounters--; }
          else { e.muted = false; }
        }
        // loopCounts is a per-TURN budget and is cleared here. entry.counters
        // is NOT: tagged counters persist until the permanent leaves play.
        e.loopCounts = {};
      }
      // max_per_turn_shared lives on the player, not the entry, so it needs its
      // own clear here. Both the fast path and the logged path reset -- missing
      // one leaks the budget across turns in half the runs.
      ap.mptShared = {};
      if (ap.shields > 0) ap.shields--;
      if (ap.poison > 0) {
        const lost = dmgPlayer(ap, ap.poison, 'poison');
        ap.poison--;
        if (lost > 0) {
          firePassives(ap, 'on_life_loss', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
          firePassives(op, 'on_opponent_life_loss', op, ap, rand, cardDefs, null, false, plans[oi], plan);
        }
        updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);
        let w = checkWin(p, turn, ht, cardDefs); if (w) return withStats(w, p);
      }

      // 0b. Chaos Arena turn-start effects -- self-inflicted, applied
      // chaosCounts[slug] times each (0/1/2; see parseChaosEffects).
      for (const ce of CHAOS_TURN_START_EFFECTS) {
        const n = chaosCounts[ce.slug] || 0;
        for (let i = 0; i < n; i++) applyEffect(ce.eff, ap, op, rand, cardDefs, null, plan, false, null, plans[oi]);
      }
      pruneBoard(ap, op, rand, cardDefs, null, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, null, plans[oi], plan);
      updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);
      { let w = checkWin(p, turn, ht, cardDefs); if (w) return withStats(w, p); }

      // 1. Draw
      if (!tryDraw(ap)) return withStats({ winner: oi, reason: 'deck_out', turns: turn }, p);
      let chaosDrawnFast = 0;
      for (let i = 0; i < (chaosCounts.extra_draw || 0); i++) { if (tryDraw(ap)) chaosDrawnFast++; }
      firePassives(ap, 'on_draw', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
      for (let xd = 0; xd < chaosDrawnFast; xd++)
        firePassives(ap, 'on_extra_draw', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
      for (let xd = 0; xd < chaosDrawnFast; xd++)
        firePassives(op, 'on_opponent_extra_draw', op, ap, rand, cardDefs, null, false, plans[oi], plan);
      firePassives(op, 'on_opponent_draw', op, ap, rand, cardDefs, null, false, plans[oi], plan);

      // 2. Basic energy
      ap.energy += 1;
      ap.energy += (chaosCounts.extra_energy || 0);

      // 3. Opponent's each_opponent_turn triggers (random order)
      fireTriggers(shuffle(op.board.slice(), rand), 'each_opponent_turn', op, ap, rand, cardDefs, null, plans[oi], plan);
      pruneBoard(ap, op, rand, cardDefs, null, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, null, plans[oi], plan);
      updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);
      let w = checkWin(p, turn, ht, cardDefs); if (w) return withStats(w, p);

      // 4. Beginning-of-turn triggers (each_turn; turn_start is legacy alias)
      fireTriggers(ap.board.slice(), 'each_turn',  ap, op, rand, cardDefs, null, plan, plans[oi]);
      fireTriggers(ap.board.slice(), 'turn_start', ap, op, rand, cardDefs, null, plan, plans[oi]);
      // 4b. v4.52: each Saga runs its next chapter
      fireSagas(ap.board.slice(), ap, op, rand, cardDefs, null, plan, plans[oi]);
      pruneBoard(ap, op, rand, cardDefs, null, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, null, plans[oi], plan);
      updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);
      w = checkWin(p, turn, ht, cardDefs); if (w) return withStats(w, p);

      // 5. Play cards (greedy); champion has highest priority
      let __playsThisTurn = 0;
      while (tryPlayChampion(ap, op, cardDefs, rand, iid, null, chaosCounts, plan, plans[oi]) || playOneCard(ap, op, cardDefs, rand, plan, plans[oi], iid, null, chaosCounts)) {
        pruneBoard(ap, op, rand, cardDefs, null, plan, plans[oi]);
        pruneBoard(op, ap, rand, cardDefs, null, plans[oi], plan);
        updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);
        w = checkWin(p, turn, ht, cardDefs); if (w) return withStats(w, p);
        if (++__playsThisTurn >= MAX_PLAYS_PER_TURN) break;
      }

      // 6. End of turn triggers
      fireTriggers(ap.board.slice(), 'end_of_turn', ap, op, rand, cardDefs, null, plan, plans[oi]);
      pruneBoard(ap, op, rand, cardDefs, null, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, null, plans[oi], plan);
      updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);
      w = checkWin(p, turn, ht, cardDefs); if (w) return withStats(w, p);

      // 6b. Armor recovery (both boards reset curArmor to per-entry base)
      recoverArmor(ap.board, cardDefs);
      recoverArmor(op.board, cardDefs);

      // 6c. Combat phase — active player's total creature melee vs opponent's total permanent armor
      {
        const attackers    = ap.board.filter(e => !e.utilized);
        const meleeOf      = attackers.map(e => effectiveMelee(e, ap.board, cardDefs));
        // linkCapOf is the basis sumCombatKWScaled multiplies each attacker's
        // link/bound amount by -- see linkCapBasis's own comment for why
        // non-creature permanents use cost, not melee.
        const linkCapOf    = attackers.map((e, i) => linkCapBasis(e.card_id, meleeOf[i], cardDefs));
        const totalMelee   = meleeOf.reduce((s, m) => s + m, 0);
        const totalOpArmor = op.board.reduce((s, e) => s + (e.utilized ? 0 : effectiveArmor(e, op.board, cardDefs)), 0);
        // Shields reduce whether combat damage triggers at all (mirrors shell/validate.go's
        // combatDmg := totalMelee - totalOpArmor - op.shields) -- unlike deal_damage_to_player,
        // shields aren't consumed here; they just naturally decay by 1/turn regardless.
        const evasion      = combatWithEvasion(attackers, meleeOf, op, cardDefs);   // v4.62
        const combatDmg    = evasion ? evasion.dmg : totalMelee - totalOpArmor - op.shields;
        if (combatDmg > 0) {
          op.life -= combatDmg; tally(op, 'life_lost', combatDmg);
          if (combatDmg > 0) op.lifeLossCause = 'combat';
          // 2026-08-08: lifelink/honorlink/shieldlink/drawlink/millbound/
          // honorbound switched from "combatDmg * (total keyword count
          // across attackers)" to a per-creature melee-scaled formula: each
          // attacker's contribution is its own live melee x its own amount
          // of the keyword (sumCombatKWScaled), summed across attackers,
          // then capped at the actual combatDmg dealt this turn
          // (Math.min(ceiling, combatDmg)) -- so a 1-melee creature no
          // longer draws/gains off the WHOLE board's pooled damage, only
          // its own contribution, while still never exceeding what
          // actually got through. (reported: Fry Guy/phodos, 2026-08-08)
          const lifelink   = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'lifelink',   cardDefs), combatDmg);
          const honorlink  = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'honorlink',  cardDefs), combatDmg);
          const drawlink   = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'drawlink',   cardDefs), combatDmg);
          const shieldlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'shieldlink', cardDefs), combatDmg);
          const millbound  = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'millbound',  cardDefs), combatDmg);
          const honorbound = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'honorbound', cardDefs), combatDmg);
          // lifelink/honorlink/shieldlink used to mutate the resource directly
          // without firing the same on_*_gain passives the equivalent explicit
          // card effect (gain_life/gain_honor/gain_shield) always fires -- e.g.
          // Soul Drinker's on_opponent_honor_gain passive silently never saw
          // honor gained via honorlink, only honor from an explicit gain_honor
          // effect. Also gave shieldlink gains no way to be blocked by an
          // opponent's Prevent Shields (prevent_opponent_shield), unlike every
          // other shield-gain source. (reported: Tristal, 2026-08-05)
          if (lifelink   > 0) {
            ap.life += lifelink; tally(ap, 'life_gained', lifelink);
            firePassives(ap, 'on_life_gain', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
            firePassives(op, 'on_opponent_life_gain', op, ap, rand, cardDefs, null, false, plans[oi], plan);
          }
          if (honorlink  > 0) {
            ap.honor += honorlink; ap.honorGainedTotal = (ap.honorGainedTotal || 0) + honorlink; tally(ap, 'honor_gained', honorlink);
            firePassives(ap, 'on_honor_gain', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
            firePassives(op, 'on_opponent_honor_gain', op, ap, rand, cardDefs, null, false, plans[oi], plan);
          }
          if (shieldlink > 0 && !hasPassive(op, 'prevent_opponent_shield', cardDefs)) {
            ap.shields += shieldlink; tally(ap, 'shields_gained', shieldlink);
            firePassives(ap, 'on_shielded', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
            firePassives(op, 'on_opponent_shielded', op, ap, rand, cardDefs, null, false, plans[oi], plan);
          }
          if (drawlink   > 0) { for (let _d = 0; _d < drawlink; _d++) tryDraw(ap); }
          if (millbound  > 0 && !hasPassive(op, 'prevent_mill', cardDefs)) {
            let mc = millbound;
            let milled = 0;
            while (mc-- > 0 && op.deck.length) { arriveInDiscard(op, op.deck.shift(), cardDefs, rand, null, 'deck', ap, plans[oi], plan); milled++; }
            ap.milledOpponentTotal = (ap.milledOpponentTotal || 0) + milled;
          }
          if (honorbound > 0 && !hasPassive(op, 'prevent_honor_loss', cardDefs)) {
            // Not floored -- opponent-facing honor drain, same as
            // reduce_opponent_honor (see the dishonor loss condition).
            op.honor -= honorbound; tally(op, 'honor_lost', honorbound);
            ap.opponentHonorLostTotal = (ap.opponentHonorLostTotal || 0) + honorbound;
            // honorbound > 0 is already guaranteed by the condition above.
            firePassives(op, 'on_honor_loss', op, ap, rand, cardDefs, null, false, plans[oi], plan);
            firePassives(ap, 'on_opponent_honor_loss', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
          }
          // 2026-08-08: Phase 2 -- 8 new combat-link keywords, same
          // melee-scaled formula as the 6 above, each just triggering an
          // existing, already-3-engine-parity'd effect payload with the
          // computed capped amount.
          const poisonlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'poisonlink', cardDefs), combatDmg);
          if (poisonlink > 0 && !hasPassive(op, 'prevent_poison', cardDefs)) {
            op.poison += poisonlink; tally(op, 'poison_gained', poisonlink);
            firePassives(op, 'on_poisoned', op, ap, rand, cardDefs, null, false, plans[oi], plan);
            firePassives(ap, 'on_opponent_poisoned', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
          }
          const damagelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'damagelink', cardDefs), combatDmg);
          if (damagelink > 0) {
            const lost = dmgPlayer(op, damagelink, 'combat');
            if (lost > 0) {
              firePassives(op, 'on_life_loss', op, ap, rand, cardDefs, null, false, plans[oi], plan);
              firePassives(ap, 'on_opponent_life_loss', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
            }
            firePassives(ap, 'on_damage_dealt', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
            firePassives(op, 'on_opponent_damage_dealt', op, ap, rand, cardDefs, null, false, plans[oi], plan);
          }
          const discardlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'discardlink', cardDefs), combatDmg);
          if (discardlink > 0) {
            const n = Math.min(discardlink, 10);
            for (let _k = 0; _k < n && op.hand.length; _k++) {
              const idx = Math.floor(rand() * op.hand.length);
              arriveInDiscard(op, op.hand.splice(idx, 1)[0], cardDefs, rand, null, 'hand', ap, plans[oi], plan);
            }
          }
          // siegelink: no cost threshold -- hits every enemy structure
          // unconditionally ('gte'/0 is this codebase's existing "match
          // everything of this supertype" idiom). The full amount applies
          // to EACH matching structure, not divided among them.
          const siegelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'siegelink', cardDefs), combatDmg);
          if (siegelink > 0) {
            for (const c of op.board) {
              if (!matchesCostFilter(cardDefs[c.card_id], 'structure', 'gte', 0)) continue;
              if (c.hp === null || c.hp <= 0 || isIndestructible(c, cardDefs)) continue;
              dmgCreature(c, siegelink);
            }
          }
          const reliclink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'reliclink', cardDefs), combatDmg);
          if (reliclink > 0) {
            for (const c of op.board.slice()) {
              if (c.destroyed || !matchesCostFilter(cardDefs[c.card_id], 'relic', 'lte', reliclink) || isIndestructible(c, cardDefs)) continue;
              c.destroyed = true;
              fireLeaveTrigger(c, op, ap, rand, cardDefs, null, undefined, plans[oi], plan);
              if (!championLeave(op, c.card_id, null, cardDefs)) toDiscardOrExile(op, c, cardDefs, rand, null, ap, plans[oi], plan);
            }
            op.board = op.board.filter(c => !c.destroyed);
          }
          const enchantmentlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'enchantmentlink', cardDefs), combatDmg);
          if (enchantmentlink > 0) {
            for (const c of op.board.slice()) {
              if (c.destroyed || !matchesCostFilter(cardDefs[c.card_id], 'enchantment', 'lte', enchantmentlink) || isIndestructible(c, cardDefs)) continue;
              c.destroyed = true;
              fireLeaveTrigger(c, op, ap, rand, cardDefs, null, undefined, plans[oi], plan);
              if (!championLeave(op, c.card_id, null, cardDefs)) toDiscardOrExile(op, c, cardDefs, rand, null, ap, plans[oi], plan);
            }
            op.board = op.board.filter(c => !c.destroyed);
          }
          // animatelink: self-directed (like lifelink/honorlink/drawlink),
          // reanimates ONE creature from the caster's own discard costing
          // <= the computed amount.
          const animatelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'animatelink', cardDefs), combatDmg);
          if (animatelink > 0) {
            const pool = reanimateCandidates(ap, cardDefs, '', 'creature', 'lte', animatelink);
            if (pool.length) {
              const cid = selectPileCard(pool, 'random', cardDefs, rand);
              const idx = ap.discard.indexOf(cid);
              if (idx !== -1) ap.discard.splice(idx, 1);
              reanimateOne(ap, op, cid, cardDefs, rand, null, undefined, undefined, plan, plans[oi]);
            }
          }
          // creaturelink: the first link/bound keyword that targets a single
          // creature -- weakest (sorted by health+armor, fully deterministic,
          // no RNG/config needed), not random, to match millbound/honorbound/
          // poisonlink's deterministic nature. Mirrors deal_damage_to_weakest_
          // creature's own inline-filter style.
          const creaturelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'creaturelink', cardDefs), combatDmg);
          if (creaturelink > 0) {
            const crlTs = op.board.filter(c => !c.destroyed && c.hp !== null && c.hp > 0 && matchesSupertype(cardDefs[c.card_id], 'creature') && !isUntargetable(c, cardDefs)).sort((x, y) => {
              const xs = cardDefs[x.card_id]?.stats_json ?? {};
              const ys = cardDefs[y.card_id]?.stats_json ?? {};
              return ((xs.health || 0) + (xs.armor || 0)) - ((ys.health || 0) + (ys.armor || 0));
            });
            if (crlTs.length && !isIndestructible(crlTs[0], cardDefs)) dmgCreature(crlTs[0], creaturelink);
          }
          // coinlink (Avarice): the only link keyword that CREATES permanents,
          // so it is capped twice -- by combatDmg like every other link, and
          // again by MAX_TOKEN_COPIES_PER_FIRE (the Rabbit Queen guard; see
          // its comment for why an uncapped token fire hung the validators).
          const coinlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'coinlink', cardDefs), combatDmg, MAX_TOKEN_COPIES_PER_FIRE);
          if (coinlink > 0 && cardDefs[COINLINK_TOKEN_ID]) {
            for (let _cl = 0; _cl < coinlink; _cl++) {
              reanimateOne(ap, op, COINLINK_TOKEN_ID, cardDefs, rand, null, '', true, plan, plans[oi]);
            }
          }
          // Champion honor link: champion's Melee as Honor when combat damage is dealt
          if (ap.championId !== null) {
            const champEntry = attackers.find(e => e.card_id === ap.championId);
            if (champEntry && champEntry.melee > 0) {
              ap.honor += champEntry.melee; tally(ap, 'honor_gained', champEntry.melee);
              ap.honorGainedTotal = (ap.honorGainedTotal || 0) + champEntry.melee;
              firePassives(ap, 'on_honor_gain', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
              firePassives(op, 'on_opponent_honor_gain', op, ap, rand, cardDefs, null, false, plans[oi], plan);
            }
          }
          firePassives(op, 'on_life_loss', op, ap, rand, cardDefs, null, false, plans[oi], plan);
          firePassives(ap, 'on_opponent_life_loss', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
          firePassives(ap, 'on_damage_dealt', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
          firePassives(op, 'on_opponent_damage_dealt', op, ap, rand, cardDefs, null, false, plans[oi], plan);
          // v4.55: combat damage only -- on_damage_dealt above also fires for
          // spells and damagelink. combatDmg is what got through armor and
          // shields; meleeDealtEvent is read by count_source zone 'melee_damage'
          // and restored after, so it reads 0 everywhere else.
          { const prevMelee = ap.meleeDealtEvent || 0; ap.meleeDealtEvent = combatDmg;
            firePassives(ap, 'on_melee_damage_dealt', ap, op, rand, cardDefs, null, false, plan, plans[oi]);
            ap.meleeDealtEvent = prevMelee; }
          updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);
          w = checkWin(p, turn, ht, cardDefs); if (w) return withStats(w, p);
        }
      }

      // 6d. After-combat triggers (v4.32). The window end_of_turn is NOT: step 6
      // above runs BEFORE armor recovery (6b) and before the combat phase
      // (6c), so a card that wants to act on the RESULT of its attack had
      // nowhere to hook -- the first thing Grieving Raven asked for (see
      // ENGINE_SPAWNED_CARDS.md). Fires on the active player's board once
      // combat has fully resolved, and fires whether or not any damage got
      // through, the same unconditional shape as end_of_turn, so a `requires`
      // gate stays the way to narrow it.
      //
      // Costs no RNG while no card carries the trigger: fireTriggers only
      // reaches applyEffect on a match, and pruneBoard with nothing dead is
      // just a filter -- so every existing replay stays bit-identical.
      //
      // NOTHING ELSE belongs in this block. An earlier version also ran
      // pruneBoard x2 + updateTurnStats + checkWin here, mirroring
      // end_of_turn's shape -- and that was NOT a no-op: combat's own
      // firePassives can leave an entry at hp<=0, which previously sat on the
      // board until the next turn's step-0 prune. Pruning it here fired its
      // on_death/on_leave earlier, consumed RNG at a different point, and
      // moved the whole game: 10 of 9,828 whole-catalog matches changed, with
      // `turns` among the fields, before this was cut back.
      //
      // So a corpse or a lethal blow from an after_combat effect is resolved
      // by the NEXT phase's prune and win check -- the opponent's step 0,
      // which runs before they draw or play anything. That matches how the
      // engine already treats a corpse created during the combat phase.
      if (boardHasTrigger(ap.board, cardDefs, 'after_combat'))
        fireTriggers(ap.board.slice(), 'after_combat', ap, op, rand, cardDefs, null, plan, plans[oi]);

      // 7. Clear ephemeral energy (base pool + any restricted-energy entries
      // flagged ephemeral -- persistent restricted-energy entries carry over)
      //
      // v4.40: the SECOND of the two step-7 sites. This loop is the one the
      // validators' results come from, so a change made only in the logged
      // loop above would work in a replay and not in a real match -- a
      // browser-vs-validator disagreement, which is the worst kind. Same rule,
      // no logging.
      if (hasPassive(ap, 'ephemeral_persists', cardDefs)) {
        ap.energy += ap.ephemeral;
        ap.ephemeral = 0;
        ap.restrictedEnergy = ap.restrictedEnergy.map(e => e.ephemeral ? { ...e, ephemeral: false } : e);
      } else {
      ap.ephemeral = 0;
      ap.restrictedEnergy = ap.restrictedEnergy.filter(e => !e.ephemeral);
      }

      // 8. Discard to hand limit
      while (ap.hand.length > MAX_HAND) {
        const idx = pickHandLimitDiscard(ap.hand, cardDefs, plan, rand);
        arriveInDiscard(ap, ap.hand.splice(idx, 1)[0], cardDefs, rand, null, 'hand', op, plan, plans[oi]);
      }

      // Finish this turn's damage/poison achievement tracking (see snapshot above)
      updateTurnStats(ap, op, turnLifeStart, turnPoisonStart);

      // Time Walk: the player who just finished goes again instead of passing.
      // The turn counter still advances, so MAX_TURNS bounds a runaway chain
      // and a lock degrades to a timeout result rather than hanging.
      // No logging here: _simSingleGame is the fast path and has no `log`
      // binding at all, so `if (log)` is a ReferenceError, not a falsy check.
      // recordBattle's copy of this guard does the logging.
      if (ap.extraTurns > 0) {
        ap.extraTurns--;
      } else {
        active = oi;
      }
    }

    const [p0, p1] = p;
    if (p0.honor !== p1.honor)
      return withStats({ winner: p0.honor > p1.honor ? 0 : 1, reason: 'timeout_honor', turns: MAX_TURNS }, p);
    if (p0.life !== p1.life)
      return withStats({ winner: p0.life  > p1.life  ? 0 : 1, reason: 'timeout_life',  turns: MAX_TURNS }, p);
    return withStats({ winner: -1, reason: 'draw', turns: MAX_TURNS }, p);
  }

  // ── Best-of-2 match: each player goes first once ─────────────────────────────
  function simulateMatch(cardsA, cardsB, cardDefs, seed, planA, planB, championA, championB, chaosEffect, startingLife, honorToWin, startA, startB) {
    const r1 = _simSingleGame(cardsA, cardsB, cardDefs, seed,                    planA, planB, 0, championA, championB, chaosEffect, startingLife, honorToWin, startA, startB);
    const r2 = _simSingleGame(cardsA, cardsB, cardDefs, (seed ^ 0x80000000) >>> 0, planA, planB, 1, championA, championB, chaosEffect, startingLife, honorToWin, startA, startB);
    const wA = (r1.winner === 0 ? 1 : 0) + (r2.winner === 0 ? 1 : 0);
    const wB = (r1.winner === 1 ? 1 : 0) + (r2.winner === 1 ? 1 : 0);
    const turns = r1.turns + r2.turns;
    // Achievement stats: best of the two individual games, per side -- a
    // best-of-2 match is two separate games, so a peak stat from either one
    // should count (never summed across both).
    const stats = {
      dmg_a: Math.max(r1.dmg_a, r2.dmg_a), dmg_b: Math.max(r1.dmg_b, r2.dmg_b),
      poison_a: Math.max(r1.poison_a, r2.poison_a), poison_b: Math.max(r1.poison_b, r2.poison_b),
      mill_a: Math.max(r1.mill_a, r2.mill_a), mill_b: Math.max(r1.mill_b, r2.mill_b),
      honor_a: Math.max(r1.honor_a, r2.honor_a), honor_b: Math.max(r1.honor_b, r2.honor_b),
    };
    // Per-GAME outcomes, for callers that want an honest breakdown. A match is
    // two games but reports a single reason (the one the winner won FIRST), so
    // tallying match reasons silently drops the other game -- a 2-0 that decked
    // them once and burned them once would count only the deck-out. Nothing
    // submitted ever sees this: runCohort() builds its pushed object from named
    // fields and hashResults() stringifies only that, so extra keys here cannot
    // change a shard hash or diverge from validate.go / validate.py.
    const games = [
      { winner: r1.winner, reason: r1.reason, cause: r1.cause ?? null },
      { winner: r2.winner, reason: r2.reason, cause: r2.cause ?? null },
    ];
    if (wA > wB) return { winner: 0, reason: r1.winner === 0 ? r1.reason : r2.reason, turns, ...stats, games };
    if (wB > wA) return { winner: 1, reason: r1.winner === 1 ? r1.reason : r2.reason, turns, ...stats, games };
    return { winner: -1, reason: 'draw', turns, ...stats, games };
  }

  // ── Payload normalisation (shell/VALIDATOR_SPEC.md, "Plan and deck value types")
  // Battle-plan scalars and card ids are JSON numbers/bools, but nothing stops
  // a client from storing the quoted form, and the four engines used to split
  // three ways on one ("804" vs 804): consensus fails. validate.py, validate.go
  // and engine.go apply these SAME rules at payload ingest, so all four agree
  // by construction; a payload with only native types passes through unchanged.
  const PLAN_INT_FIELDS      = ['energy_hold', 'supplement_reserve', 'champion_energy_threshold'];
  const PLAN_BOOL_FIELDS     = ['card_order_hold', 'discard_gravecast_first', 'hold_removal_without_target', 'prefer_new_names'];   // v4.69: last two
  const PLAN_STR_FIELDS      = ['play_priority', 'supplements', 'energy_hold_scope', 'gravecast_policy',
                                'target_preference', 'self_target_preference', 'cost_preference',
                                'discard_priority', 'champion_timing'];
  const PLAN_STR_LIST_FIELDS = ['type_order', 'tag_order', 'target_tag_order'];
  const PLAN_INT_LIST_FIELDS = ['card_order'];
  const PLAN_KNOWN = new Set([...PLAN_INT_FIELDS, ...PLAN_BOOL_FIELDS, ...PLAN_STR_FIELDS,
                              ...PLAN_STR_LIST_FIELDS, ...PLAN_INT_LIST_FIELDS]);
  const BAD = Symbol('bad');
  function normInt(v) {
    if (typeof v === 'number') return Number.isInteger(v) ? v : BAD;
    if (typeof v === 'string' && /^-?[0-9]+$/.test(v)) return parseInt(v, 10);
    return BAD;
  }
  function normBool(v) {
    if (typeof v === 'boolean') return v;
    if (v === 'true') return true;
    if (v === 'false') return false;
    return BAD;
  }
  // { plan, reason }. Quoted ints/bools become native and null fields are
  // dropped (Go reads a null as the zero value, i.e. absent). A plan that still
  // has a wrong-typed field is ABSENT (null) -- what Go has always done with a
  // plan it cannot unmarshal -- and reason says why.
  function normalisePlan(plan) {
    if (plan === null || plan === undefined) return { plan: null, reason: null };
    if (typeof plan !== 'object' || Array.isArray(plan)) {
      return { plan: null, reason: `battle plan is ${Array.isArray(plan) ? 'an array' : 'a ' + typeof plan}, not an object` };
    }
    const out = { ...plan };
    for (const [k, v] of Object.entries(plan)) {
      if (v === null && PLAN_KNOWN.has(k)) { delete out[k]; continue; }
      let bad = false;
      if (PLAN_INT_FIELDS.includes(k))           { const n = normInt(v);  if (n === BAD) bad = 'is not an integer'; else out[k] = n; }
      else if (PLAN_BOOL_FIELDS.includes(k))     { const b = normBool(v); if (b === BAD) bad = 'is not a boolean';  else out[k] = b; }
      else if (PLAN_STR_FIELDS.includes(k))      { if (typeof v !== 'string') bad = 'is not a string'; }
      else if (PLAN_STR_LIST_FIELDS.includes(k)) { if (!Array.isArray(v) || !v.every(x => typeof x === 'string')) bad = 'is not a list of strings'; }
      else if (PLAN_INT_LIST_FIELDS.includes(k)) {
        if (!Array.isArray(v)) bad = 'is not a list';
        else { const ns = v.map(normInt); if (ns.includes(BAD)) bad = 'is not a list of integers'; else out[k] = ns; }
      }
      if (bad) return { plan: null, reason: `${k}: ${JSON.stringify(v)} ${bad}` };
    }
    return { plan: out, reason: null };
  }
  // A slot map with every deck id and plan normalised. Copies; the caller's
  // payload is never modified. A deck id that is not an integer at all is
  // left alone: Go rejects that payload outright, as it always has.
  function normalisedSlotMap(slots) {
    return Object.fromEntries(slots.map(s => {
      const cards = Array.isArray(s.cards) ? s.cards.map(c => { const n = normInt(c); return n === BAD ? c : n; }) : s.cards;
      const { plan, reason } = normalisePlan(s.battle_plan);
      if (reason && typeof console !== 'undefined') console.warn(`slot ${s.slot_id}: battle plan ignored -- ${reason}`);
      return [s.slot_id, { ...s, cards, battle_plan: plan }];
    }));
  }

  // ── Cohort runner ─────────────────────────────────────────────────────────────
  function runCohort(payload, onProgress) {
    const { cards, slots, matches } = payload;
    const chaosEffect = payload.chaos_effect || null;
    // Practice-round cohorts (beginner_run_practice_round()) set these to scale
    // life/honor-to-win with the (matched) deck size; absent for every real
    // cohort, which falls back to the standard 100/100 via simulateMatch's
    // own defaults.
    const startingLife = payload.starting_life ?? null;
    const honorToWin    = payload.honor_to_win  ?? null;
    const slotMap = normalisedSlotMap(slots);
    const results = [];
    for (let i = 0; i < matches.length; i++) {
      const m   = matches[i];
      const sA  = slotMap[m.slot_a];
      const sB  = slotMap[m.slot_b];
      const raw = simulateMatch(sA.cards, sB.cards, cards, m.seed,
                                sA.battle_plan, sB.battle_plan,
                                sA.champion_id ?? null, sB.champion_id ?? null, chaosEffect,
                                startingLife, honorToWin, slotStartOverride(sA), slotStartOverride(sB));
      results.push({
        match_id:    m.match_id,
        winner_slot: raw.winner === -1 ? null : (raw.winner === 0 ? m.slot_a : m.slot_b),
        reason:      raw.reason,
        turns:       raw.turns,
        // Achievement stats (game/player.php badges) -- "a"/"b" consistently
        // means slot_a/slot_b all the way from simulateMatch's cardsA/cardsB
        // through p[0]/p[1], so these need no relabeling here.
        dmg_a: raw.dmg_a, dmg_b: raw.dmg_b,
        poison_a: raw.poison_a, poison_b: raw.poison_b,
        mill_a: raw.mill_a, mill_b: raw.mill_b,
        honor_a: raw.honor_a, honor_b: raw.honor_b,
      });
      if (onProgress) onProgress(i + 1, matches.length);
    }
    return results;
  }

  // ── Result hashing (SHA-256) ──────────────────────────────────────────────────
  async function hashResults(results) {
    const sorted = results.slice().sort((a, b) => a.match_id < b.match_id ? -1 : 1);
    const text   = JSON.stringify(sorted);
    if (typeof crypto !== 'undefined' && crypto.subtle) {
      const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
      return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
    }
    const { createHash } = require('crypto');
    return createHash('sha256').update(text).digest('hex');
  }

  // ── Player state snapshot (used by recordBattle for visualizer turn data) ─────
  function snapPlayer(p, cardDefs) {
    return {
      life:      p.life,
      honor:     p.honor,
      energy:    p.energy,
      ephemeral: p.ephemeral,
      shields:   p.shields,
      poison:    p.poison,
      handCount: p.hand.length,
      hand: p.hand.map(id => ({
        card_id:   id,
        name:      cardDefs[id]?.name      ?? String(id),
        supertype: cardDefs[id]?.supertype ?? '',
        rarity:    cardDefs[id]?.rarity    ?? '',
      })),
      deckCount: p.deck.length,
      board: p.board.map(e => ({
        card_id:  e.card_id,
        name:     cardDefs[e.card_id]?.name ?? String(e.card_id),
        iid:      e.iid,
        hp:       e.hp,
        maxHp:    cardDefs[e.card_id]?.stats_json?.health ?? null,
        curArmor: e.curArmor,
        utilized: e.utilized,
        // v4.19: without these the viewer shows a permanent mysteriously not
        // untapping. The chains overlay in battle-visualizer.js reads them.
        stunned:  !!e.stunned,   // display only since v4.19 chose the global armor rule
        stunCounters: e.stunCounters || 0,
        // v4.22 Mute, same reason: without these the viewer shows a permanent
        // whose printed abilities simply never happen. muted is the derived
        // predicate so the overlay never has to recombine the two fields.
        muted:    isMuted(e),
        muteCounters: e.muteCounters || 0,
        mutedFlag: !!e.muted,
        mutePermanent: !!e.mutePermanent,
        isChampion: e.card_id === p.championId,
        isToken:  !!e.isToken,
        // Live anthem-aura bonus this entry is currently receiving from other
        // permanents on the same board (js/battle-visualizer.js highlights
        // these on the card). curArmor above already has anthem_armor baked
        // in from the turn loop's armor-recovery step; anthemArmor here is
        // just the portion of it attributable to an aura, for the visual
        // indicator -- melee has no such live field elsewhere, since the
        // real sim only ever adds anthemBonus() at combat-calculation time
        // rather than storing it per-entry.
        anthemMelee: anthemBonus(e.card_id, p.board, cardDefs, 'anthem_melee', e),
        anthemArmor: anthemBonus(e.card_id, p.board, cardDefs, 'anthem_armor', e),
        // v3.99: the EFFECTIVE stats, straight from the same helpers combat
        // uses. The visualiser previously rebuilt melee itself as
        // `printed + anthemMelee`, which silently ignored everything stored on
        // the ENTRY -- chaos melee boosts were already invisible, and stat
        // counters would have been too. Shipping the computed value means the
        // board shows what the sim actually fights with, by construction.
        melee:         effectiveMelee(e, p.board, cardDefs),
        armor:         effectiveArmor(e, p.board, cardDefs),
        meleeCounters: e.meleeCounters || 0,
        armorCounters: e.armorCounters || 0,
        // v4.36 tagged counters, omitted entirely when the entry has none --
        // snapPlayer ships the whole board every turn, so an always-present
        // empty object would grow every replay for the sake of nothing.
        counters: (e.counters && Object.keys(e.counters).length) ? { ...e.counters } : undefined,
        // v4.52 the replay's chapter badge (III/IX), Sagas only.
        ...(sagaPassive(cardDefs[e.card_id])
          ? { chapter: e.chapter || 0, chapters: sagaPassive(cardDefs[e.card_id]).chapters } : {}),
      })),
      discardCount: p.discard.length,
      // The WHOLE pile, ids only -- the viewer has every card def already, so
      // names and art cost nothing extra here. discardTop stays as it is; it
      // feeds the pile's face and three objects are cheaper than looking them
      // up. Measured over 12 real matches: +3 KB on a 380 KB turnData (~1%),
      // against a pile that reaches 77 cards. snapPlayer() is called ONLY by
      // recordBattle (zero uses in _simSingleGame), so this costs the
      // validators nothing and needs no engine version bump.
      discardAll: p.discard.slice(),
      discardTop: p.discard.slice(-3).reverse().map(id => ({
        card_id:   id,
        name:      cardDefs[id]?.name      ?? String(id),
        supertype: cardDefs[id]?.supertype ?? '',
        rarity:    cardDefs[id]?.rarity    ?? '',
      })),
      championId:      p.championId,
      championCostInc: p.championCostInc,
      championInZone:  p.championInZone,
      championName:    p.championId ? (cardDefs[p.championId]?.name ?? null) : null,
      championCost:    p.championId ? ((cardDefs[p.championId]?.cost ?? 0) + p.championCostInc) : null,
    };
  }

  // ── Battle recorder — same logic but captures a human-readable log ────────────
  // firstPlayer: 0 = A goes first, 1 = B goes first, undefined = random (legacy).
  // Pass 0 or 1 to replay a specific game from simulateMatch (avoids the extra
  // rand() call that would shift the RNG and produce a different game than the
  // cohort simulation).
  //
  // Returns { lines, result, turnData } where turnData is an array of per-turn
  // state snapshots used by the battle visualizer. Each entry: { turnNum, halfTurn,
  // player, lineStart, lineEnd, pBefore[2], pAfter[2] }. pBefore/pAfter index [0]
  // always = slotA, [1] always = slotB regardless of who goes first.
  function recordBattle(cardsA, cardsB, cardDefs, seed, planA, planB, firstPlayer, championA, championB, chaosEffect, startingLife, honorToWin, startA, startB) {
    const rand  = RNG(seed);
    const deckA = shuffle(cardsA, rand);
    const deckB = shuffle(cardsB, rand);
    // Default to A-goes-first (never coin-flip via rand()) when the caller
    // omits firstPlayer -- a conditional rand() call here consumes one more
    // RNG draw than _simSingleGame ever does for the same seed, permanently
    // shifting every subsequent random decision this game makes. That's
    // silently harmless until some later pick (shuffle/target/discard
    // selection) actually lands on a different value because of the shift,
    // at which point this single-game replay diverges from simulateMatch's
    // same-seed game 1 in ways that look like a real gameplay difference
    // (confirmed: an 419-card regression sweep found runCohort() and
    // runCohortWithLogs() disagreeing on match outcomes for identical
    // payloads -- traced to exactly this extra call, 2026-08-03).
    const first = (firstPlayer === 0 || firstPlayer === 1) ? firstPlayer : 0;
    const p     = [
      mkPlayer(deckA, championA ?? null, resolveStartingLife(startA, startingLife), resolveStartingHonor(startA), resolveStartingShields(startA)),
      mkPlayer(deckB, championB ?? null, resolveStartingLife(startB, startingLife), resolveStartingHonor(startB), resolveStartingShields(startB)),
    ];
    // Back-pointers so a board entry can reach the OTHER board without every
    // caller threading both players -- see entryHasKeyword / entry.owner.
    p[0].opponent = p[1]; p[1].opponent = p[0];
    // Stable identity for pruneBoard's on_death/on_leave log-attribution fix
    // (shared helper, used by both _simSingleGame and recordBattle) -- see
    // that function's comment.
    p[0].pIndex = 0;
    p[1].pIndex = 1;
    const plans = [planA || null, planB || null];
    __iid.val = 0;
    const iid = __iid;
    const chaos = chaosEffect || null;
    const chaosCounts = parseChaosEffects(chaos);
    const ht    = honorToWin ?? ((chaosCounts.honor_threshold_50 > 0) ? 50 : HONOR_TO_WIN);
    const lines    = [];
    const turnData = [];

    const pn    = i => `player${i + 1}`;
    const push  = msg => lines.push(msg);
    // Structured events for this recording. Reset per call so a second
    // recordBattle() never inherits the first one's stream.
    __events = [];
    __snap   = 0;
    // Flush one passive broadcast's lines under an "[event] playerN: " label.
    // A §src marker must pass through UNWRAPPED: every parser matches it at
    // the START of the line, so wrapping it produced a bubble reading
    // "ON DAMAGE DEALT: §src:1040" (reported 2026-10-13). fireTriggerOn's own
    // flush has always had this rule; these sites predate markSrc emitting
    // markers here, so they never had to.
    const flushPassive = (sub, event, who) => {
      for (const l of sub) {
        const t = String(l).trim();
        if (t.startsWith('§src:') || t.startsWith('[on_') || t.startsWith('[chapter]')) push(`  ${t}`);
        else push(`  [${event}] ${who}: ${l}`);
      }
    };

    push(`--game start--`);
    for (const slug of Object.keys(chaosCounts)) {
      const n = chaosCounts[slug];
      push(`  chaos effect active: ${CHAOS_LABELS[slug] || slug}${n > 1 ? ` ×${n}` : ''}`);
    }
    push(`first: ${pn(first)}, deck sizes: ${cardsA.length} vs ${cardsB.length}`);

    const draws = first === 0 ? [5, 6] : [6, 5];
    draws[0] += resolveStartingExtraCards(startA);
    draws[1] += resolveStartingExtraCards(startB);
    for (let i = 0; i < 2; i++) {
      for (let j = 0; j < draws[i]; j++) tryDraw(p[i]);
      push(`${pn(i)}: opening hand of ${p[i].hand.length} cards`);
    }

    let active = first;

    for (let turn = 0; turn < MAX_TURNS; turn++) {
      const ai = active, oi = 1 - active;
      const ap = p[ai], op = p[oi];
      const plan = plans[ai];

      // Snapshot board state before this turn's effects begin
      const lineStart  = lines.length;
      // The index finalizeTurn() will push this half-turn to, so every event
      // emitted below is stamped with the snapshot a viewer will look it up in.
      __snap = turnData.length;
      const snapBefore = [snapPlayer(p[0], cardDefs), snapPlayer(p[1], cardDefs)];

      // Helper: record this turn in turnData and return a result object.
      // Used for all early-exit returns so the visualizer has a complete record.
      const finalizeTurn = (result) => {
        turnData.push({
          turnNum:   Math.floor(turn / 2) + 1,
          halfTurn:  turn,
          player:    ai,
          lineStart,
          lineEnd:   lines.length,
          pBefore:   snapBefore,
          pAfter:    [snapPlayer(p[0], cardDefs), snapPlayer(p[1], cardDefs)],
        });
        return { lines, result, turnData, events: __events };
      };

      // v4.42: zero BOTH players' per-turn counters, not just the active
      // player's. "This turn" has to mean the same window on both sides or an
      // opponent-side gate reads a stale number: with a per-player reset the
      // inactive player's tally would still hold THEIR last turn's events plus
      // whatever happened to them during this one -- two turns mixed together,
      // which is not what "your opponent gained 3 life this turn" says.
      //
      // BOTH turn loops need this. The fast loop is where submitted results
      // come from and the logged loop is the replay, so a reset in only one
      // makes the browser and the validators disagree (the v4.40
      // ephemeral_persists lesson).
      ap.turnTally = {};
      op.turnTally = {};
      push(`${pn(ai)}: turn ${Math.floor(turn / 2) + 1} begins`);
      push(`  ${ap.life}hp  ${ap.honor} honor  ${ap.energy}e +${ap.ephemeral}eph  ${ap.shields} shields  ${ap.poison} poison  hand:${ap.hand.length}  deck:${ap.deck.length}  board:${ap.board.length}`);

      // 0. Refresh utilized permanents; then shield decay + poison damage
      // v4.19 Stun: a stunned permanent spends one counter here INSTEAD of
      // untapping, so Stun N costs it N untaps and Stun 0 simply untaps as
      // normal on this very pass (it was tapped for the opponent's turn, which
      // is the whole of Stun 0). loopCounts resets either way -- it is
      // unrelated bookkeeping and starving it would be a second, hidden effect.
      for (const e of ap.board) {
        if (e.stunCounters > 0) { e.stunCounters--; }
        else { e.utilized = false; e.stunned = false; }
        // v4.22 Mute ticks on the SAME schedule as stun, and in the same
        // shape: a counter is spent HERE INSTEAD of clearing the flag, so the
        // permanent is still muted for this turn's trigger phase (step 4,
        // which runs after this) and Mute N costs exactly N of the owner's
        // trigger phases -- whoever applied it and on whose turn. Decrementing
        // and letting isMuted() read the counter directly silences only N-1
        // turns, because the last decrement lands before the phase it was
        // meant to cover. mutePermanent (Mute 0) never ticks: it ends only
        // when the permanent leaves play, and the entry goes with it.
        if (!e.mutePermanent) {
          if (e.muteCounters > 0) { e.muteCounters--; }
          else { e.muted = false; }
        }
        // loopCounts is a per-TURN budget and is cleared here. entry.counters
        // is NOT: tagged counters persist until the permanent leaves play.
        e.loopCounts = {};
      }
      // max_per_turn_shared lives on the player, not the entry, so it needs its
      // own clear here. Both the fast path and the logged path reset -- missing
      // one leaks the budget across turns in half the runs.
      ap.mptShared = {};
      if (ap.shields > 0) {
        ap.shields--;
        push(`${pn(ai)}: 1 shield decays → ${ap.shields} shields remaining`);
      }
      if (ap.poison > 0) {
        const pdmg = ap.poison;
        const pblocked = Math.min(ap.shields, pdmg);
        const plost = dmgPlayer(ap, pdmg, 'poison');
        ap.poison--;
        if (pblocked > 0)
          push(`${pn(ai)}: ${pdmg} poison damage (${pblocked} blocked by shields) → ${ap.life} life, ${ap.shields} shields, ${ap.poison} poison remaining`);
        else
          push(`${pn(ai)}: ${pdmg} poison damage → ${ap.life} life, ${ap.poison} poison remaining`);
        if (plost > 0) {
          const poisonLossLog = [];
          firePassives(ap, 'on_life_loss', ap, op, rand, cardDefs, poisonLossLog, false, plan, plans[oi]);
          flushPassive(poisonLossLog, 'on_life_loss', pn(ai));
          const oppPoisonLossLog = [];
          firePassives(op, 'on_opponent_life_loss', op, ap, rand, cardDefs, oppPoisonLossLog, false, plans[oi], plan);
          flushPassive(oppPoisonLossLog, 'on_opponent_life_loss', pn(oi));
        }
        const w = checkWin(p, turn, ht, cardDefs, lines);
        if (w) { push(`--game end--`); return finalizeTurn(w); }
      }

      // 0b. Chaos Arena turn-start effects -- self-inflicted, applied
      // chaosCounts[slug] times each (0/1/2; see parseChaosEffects).
      const chaosStartLog = [];
      for (const ce of CHAOS_TURN_START_EFFECTS) {
        const n = chaosCounts[ce.slug] || 0;
        for (let i = 0; i < n; i++) applyEffect(ce.eff, ap, op, rand, cardDefs, chaosStartLog, plan, false, null, plans[oi]);
      }
      if (chaosStartLog.length) {
        push(`${pn(ai)}: chaos turn-start effects:`);
        chaosStartLog.forEach(l => push(`  ${l}`));
      }
      pruneBoard(ap, op, rand, cardDefs, lines, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, lines, plans[oi], plan);
      {
        const w = checkWin(p, turn, ht, cardDefs, lines);
        if (w) { push(`--game end--`); return finalizeTurn(w); }
      }

      // 1. Draw
      const hb = ap.hand.length;
      if (!tryDraw(ap)) {
        push(`${pn(ai)}: tried to draw — deck empty, loses`);
        push(`--game end--`);
        return finalizeTurn({ winner: oi, reason: 'deck_out', turns: turn });
      }
      const extraDraws = chaosCounts.extra_draw || 0;
      let chaosDrawn = 0;
      for (let i = 0; i < extraDraws; i++) { if (tryDraw(ap)) chaosDrawn++; }
      push(`${pn(ai)}: draws a card${extraDraws ? ` (+${extraDraws} chaos extra)` : ''} (${hb} → ${ap.hand.length} in hand)`);
      { const drawLog = []; firePassives(ap, 'on_draw', ap, op, rand, cardDefs, drawLog, false, plan, plans[oi]); flushPassive(drawLog, 'on_draw', pn(ai)); }
      // The turn's OWN draw is not extra; a chaos bonus draw is.
      if (chaosDrawn) {
        const xLog = [];
        for (let xd = 0; xd < chaosDrawn; xd++)
          firePassives(ap, 'on_extra_draw', ap, op, rand, cardDefs, xLog, false, plan, plans[oi]);
        flushPassive(xLog, 'on_extra_draw', pn(ai));
        const oxLog = [];
        for (let xd = 0; xd < chaosDrawn; xd++)
          firePassives(op, 'on_opponent_extra_draw', op, ap, rand, cardDefs, oxLog, false, plans[oi], plan);
        flushPassive(oxLog, 'on_opponent_extra_draw', pn(oi));
      }
      { const oppDrawLog = []; firePassives(op, 'on_opponent_draw', op, ap, rand, cardDefs, oppDrawLog, false, plans[oi], plan); flushPassive(oppDrawLog, 'on_opponent_draw', pn(oi)); }

      // 2. Basic energy
      const extraEnergy = chaosCounts.extra_energy || 0;
      ap.energy += 1 + extraEnergy;
      push(`${pn(ai)}: gains ${extraEnergy ? `${1 + extraEnergy} energy (chaos +${extraEnergy})` : '1 energy'} (${ap.energy} basic, ${ap.ephemeral} ephemeral)`);

      // 3. Opponent's each_opponent_turn triggers (random order)
      const oppEntries = shuffle(op.board.slice(), rand);
      const oppLog = [];
      fireTriggers(oppEntries, 'each_opponent_turn', op, ap, rand, cardDefs, oppLog, plans[oi], plan);
      if (oppLog.length) {
        push(`${pn(oi)}'s permanents trigger on ${pn(ai)}'s turn:`);
        oppLog.forEach(l => push(`  ${l}`));
      }
      pruneBoard(ap, op, rand, cardDefs, lines, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, lines, plans[oi], plan);
      let w = checkWin(p, turn, ht, cardDefs, lines);
      if (w) { push(`--game end--`); return finalizeTurn(w); }

      // 4. Beginning-of-turn triggers
      const botLog = [];
      fireTriggers(ap.board.slice(), 'each_turn',  ap, op, rand, cardDefs, botLog, plan, plans[oi]);
      fireTriggers(ap.board.slice(), 'turn_start', ap, op, rand, cardDefs, botLog, plan, plans[oi]);
      // 4b. v4.52: each Saga runs its next chapter
      fireSagas(ap.board.slice(), ap, op, rand, cardDefs, botLog, plan, plans[oi]);
      if (botLog.length) {
        push(`${pn(ai)}'s permanents trigger (beginning of turn):`);
        botLog.forEach(l => push(`  ${l}`));
      }
      pruneBoard(ap, op, rand, cardDefs, lines, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, lines, plans[oi], plan);
      w = checkWin(p, turn, ht, cardDefs, lines);
      if (w) { push(`--game end--`); return finalizeTurn(w); }

      // 5. Play cards; champion has highest priority
      let anyPlayed = false;
      let __playsThisTurn = 0;
      const playLog = [];
      // v4.40. One "could not play" explanation per card per turn -- see
      // playOneCard's own note. Logged loop only; the fast loop passes no log
      // and never reaches that branch.
      ap.blockedLogged = new Set();
      while (tryPlayChampion(ap, op, cardDefs, rand, iid, playLog, chaosCounts, plan, plans[oi]) || playOneCard(ap, op, cardDefs, rand, plan, plans[oi], iid, playLog, chaosCounts)) {
        anyPlayed = true;
        pruneBoard(ap, op, rand, cardDefs, playLog, plan, plans[oi]);
        pruneBoard(op, ap, rand, cardDefs, playLog, plans[oi], plan);
        w = checkWin(p, turn, ht, cardDefs, lines);
        if (w) {
          playLog.forEach(l => push(l));
          push(`--game end--`);
          return finalizeTurn(w);
        }
        if (++__playsThisTurn >= MAX_PLAYS_PER_TURN) {
          playLog.push(`  (played ${MAX_PLAYS_PER_TURN} cards this turn — stopping here, further plays skipped)`);
          break;
        }
      }
      // v4.41 FIX to v4.40's bounded log: this was `if (anyPlayed)`, which
      // threw the whole buffer away on any turn where nothing was played --
      // and those are exactly the turns the explanation exists for. Caution to
      // the Wind sitting in hand at 6 shields on a turn with no other play was
      // the reported case, and it was still silent. The "passes" line stays,
      // because "nothing was played" and "here is why" are both worth saying.
      if (playLog.length) playLog.forEach(l => push(l));
      if (!anyPlayed) push(`${pn(ai)}: no cards to play, passes`);

      // 6. End of turn triggers
      const eotLog = [];
      fireTriggers(ap.board.slice(), 'end_of_turn', ap, op, rand, cardDefs, eotLog, plan, plans[oi]);
      if (eotLog.length) {
        push(`${pn(ai)}'s permanents trigger (end of turn):`);
        eotLog.forEach(l => push(`  ${l}`));
      }
      pruneBoard(ap, op, rand, cardDefs, lines, plan, plans[oi]);
      pruneBoard(op, ap, rand, cardDefs, lines, plans[oi], plan);
      w = checkWin(p, turn, ht, cardDefs, lines);
      if (w) { push(`--game end--`); return finalizeTurn(w); }

      // 6b. Armor recovery (all permanents reset curArmor to per-entry base)
      recoverArmor(ap.board, cardDefs);
      recoverArmor(op.board, cardDefs);

      // 6c. Combat phase — active player's non-utilized creature melee vs opponent's total armor
      {
        const attackers    = ap.board.filter(e => !e.utilized);
        const meleeOf      = attackers.map(e => effectiveMelee(e, ap.board, cardDefs));
        // linkCapOf is the basis sumCombatKWScaled multiplies each attacker's
        // link/bound amount by -- see linkCapBasis's own comment for why
        // non-creature permanents use cost, not melee.
        const linkCapOf    = attackers.map((e, i) => linkCapBasis(e.card_id, meleeOf[i], cardDefs));
        const totalMelee   = meleeOf.reduce((s, m) => s + m, 0);
        const totalOpArmor = op.board.reduce((s, e) => s + (e.utilized ? 0 : effectiveArmor(e, op.board, cardDefs)), 0);
        // Shields reduce whether combat damage triggers at all (mirrors shell/validate.go's
        // combatDmg := totalMelee - totalOpArmor - op.shields) -- unlike deal_damage_to_player,
        // shields aren't consumed here; they just naturally decay by 1/turn regardless.
        const evasion      = combatWithEvasion(attackers, meleeOf, op, cardDefs);   // v4.62
        const combatDmg    = evasion ? evasion.dmg : totalMelee - totalOpArmor - op.shields;
        if (totalMelee > 0) {
          if (evasion) {
            const kw = [evasion.flyingMelee ? `${evasion.flyingMelee} flying` : '', evasion.shieldbreakerMelee ? `${evasion.shieldbreakerMelee} shieldbreaker` : ''].filter(Boolean).join(', ');
            push(`${pn(ai)}: combat — ${totalMelee} melee (non-utilized${kw ? '; ' + kw : ''}) vs ${totalOpArmor} opponent armor (${evasion.flyingArmor} on flyers) + ${op.shields} shields → ${combatDmg} damage`);
          } else {
            push(`${pn(ai)}: combat — ${totalMelee} melee (non-utilized) vs ${totalOpArmor} opponent armor + ${op.shields} shields → ${combatDmg} damage`);
          }
        }
        if (combatDmg > 0) {
          op.life -= combatDmg; tally(op, 'life_lost', combatDmg);
          if (combatDmg > 0) op.lifeLossCause = 'combat';
          push(`${pn(oi)}: ${combatDmg} combat damage → ${op.life} life`);
          // 2026-08-08: switched from "combatDmg * (total keyword count
          // across attackers)" to a per-creature melee-scaled formula --
          // see the equivalent fast-path block above for the full comment.
          // Values below are already the final applied amount (post-cap),
          // no more `combatDmg * x` at any use site.
          const lifelink   = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'lifelink',   cardDefs), combatDmg);
          const honorlink  = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'honorlink',  cardDefs), combatDmg);
          const drawlink   = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'drawlink',   cardDefs), combatDmg);
          const shieldlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'shieldlink', cardDefs), combatDmg);
          const millbound  = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'millbound',  cardDefs), combatDmg);
          const honorbound = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'honorbound', cardDefs), combatDmg);
          // See the equivalent fast-path block above for why the on_*_gain fires
          // and the prevent_opponent_shield check are here now -- same bug,
          // both paths (reported: Tristal, 2026-08-05). Also brings
          // honorGainedTotal tracking in line with the fast path, which this
          // logged path was silently missing for honorlink (matches the
          // millbound gap already fixed above in this same function).
          if (lifelink   > 0) {
            ap.life += lifelink; push(`  lifelink: ${pn(ai)} +${lifelink} life → ${ap.life}`); tally(ap, 'life_gained', lifelink);
            const lgl = []; firePassives(ap, 'on_life_gain', ap, op, rand, cardDefs, lgl, false, plan, plans[oi]); flushPassive(lgl, 'on_life_gain', pn(ai));
            const olgl = []; firePassives(op, 'on_opponent_life_gain', op, ap, rand, cardDefs, olgl, false, plans[oi], plan); flushPassive(olgl, 'on_opponent_life_gain', pn(oi));
          }
          if (honorlink  > 0) {
            ap.honor += honorlink; ap.honorGainedTotal = (ap.honorGainedTotal || 0) + honorlink; tally(ap, 'honor_gained', honorlink);
            push(`  honorlink: ${pn(ai)} +${honorlink} honor → ${ap.honor}`);
            const hgl = []; firePassives(ap, 'on_honor_gain', ap, op, rand, cardDefs, hgl, false, plan, plans[oi]); flushPassive(hgl, 'on_honor_gain', pn(ai));
            const ohgl = []; firePassives(op, 'on_opponent_honor_gain', op, ap, rand, cardDefs, ohgl, false, plans[oi], plan); flushPassive(ohgl, 'on_opponent_honor_gain', pn(oi));
          }
          if (shieldlink > 0) {
            if (hasPassive(op, 'prevent_opponent_shield', cardDefs)) {
              push(`  shieldlink: prevented by ${pn(oi)}'s passive`);
            } else {
              ap.shields += shieldlink; push(`  shieldlink: ${pn(ai)} +${shieldlink} shields → ${ap.shields}`); tally(ap, 'shields_gained', shieldlink);
              const sgl = []; firePassives(ap, 'on_shielded', ap, op, rand, cardDefs, sgl, false, plan, plans[oi]); flushPassive(sgl, 'on_shielded', pn(ai));
              const osgl = []; firePassives(op, 'on_opponent_shielded', op, ap, rand, cardDefs, osgl, false, plans[oi], plan); flushPassive(osgl, 'on_opponent_shielded', pn(oi));
            }
          }
          if (drawlink   > 0) {
            let drew = 0;
            for (let _d = 0; _d < drawlink; _d++) { if (tryDraw(ap)) drew++; }
            push(`  drawlink: ${pn(ai)} drew ${drew} card(s) (${ap.hand.length} in hand)`);
            // Same partial-exhaustion gap as draw_card: drawlink is exactly how
            // an attacker decks themselves out on a lethal swing.
            if (drew < drawlink) push(`  drawlink: ${pn(ai)} tried to draw ${drawlink - drew} more — deck empty`);
          }
          if (millbound  > 0) {
            if (hasPassive(op, 'prevent_mill', cardDefs)) {
              push(`  millbound: mill prevented by ${pn(oi)}'s passive`);
            } else {
              let mc = millbound, milled = 0;
              while (mc-- > 0 && op.deck.length) { arriveInDiscard(op, op.deck.shift(), cardDefs, rand, lines, 'deck', ap, plans[oi], plan); milled++; }
              // Missing entirely before this fix (found while tracing an
              // unrelated mill-count bug) -- the fast path's own millbound
              // block (used by runCohort) does this; this logged/replay
              // path (runCohortWithLogs, feeds the visualizer) never did,
              // so a replay's own mill_a/mill_b stat undercounted whenever
              // millbound triggered.
              ap.milledOpponentTotal = (ap.milledOpponentTotal || 0) + milled;
              push(`  millbound: ${pn(oi)} milled ${milled} card(s) (${op.deck.length} remain)`);
            }
          }
          if (honorbound > 0) {
            if (hasPassive(op, 'prevent_honor_loss', cardDefs)) {
              push(`  honorbound: honor loss prevented by ${pn(oi)}'s passive`);
            } else {
              // Not floored -- opponent-facing honor drain (dishonor loss condition).
              op.honor -= honorbound; tally(op, 'honor_lost', honorbound);
              push(`  honorbound: ${pn(oi)} -${honorbound} honor → ${op.honor}`);
              const hbl = []; firePassives(op, 'on_honor_loss', op, ap, rand, cardDefs, hbl, false, plans[oi], plan); flushPassive(hbl, 'on_honor_loss', pn(oi));
              const ohbl = []; firePassives(ap, 'on_opponent_honor_loss', ap, op, rand, cardDefs, ohbl, false, plan, plans[oi]); flushPassive(ohbl, 'on_opponent_honor_loss', pn(ai));
            }
          }
          // 2026-08-08: Phase 2 -- 8 new combat-link keywords, same
          // melee-scaled formula as the 6 above -- see the equivalent
          // fast-path block above for the full comment.
          const poisonlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'poisonlink', cardDefs), combatDmg);
          if (poisonlink > 0) {
            if (hasPassive(op, 'prevent_poison', cardDefs)) {
              push(`  poisonlink: prevented by ${pn(oi)}'s passive`);
            } else {
              op.poison += poisonlink; push(`  poisonlink: ${pn(oi)} +${poisonlink} poison → ${op.poison}`); tally(op, 'poison_gained', poisonlink);
              const pgl = []; firePassives(op, 'on_poisoned', op, ap, rand, cardDefs, pgl, false, plans[oi], plan); flushPassive(pgl, 'on_poisoned', pn(oi));
              const opgl = []; firePassives(ap, 'on_opponent_poisoned', ap, op, rand, cardDefs, opgl, false, plan, plans[oi]); flushPassive(opgl, 'on_opponent_poisoned', pn(ai));
            }
          }
          const damagelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'damagelink', cardDefs), combatDmg);
          if (damagelink > 0) {
            const lost = dmgPlayer(op, damagelink, 'combat');
            push(`  damagelink: ${pn(oi)} took ${damagelink} bonus damage → ${op.life} life`);
            if (lost > 0) {
              const dll = []; firePassives(op, 'on_life_loss', op, ap, rand, cardDefs, dll, false, plans[oi], plan); flushPassive(dll, 'on_life_loss', pn(oi));
              const odll = []; firePassives(ap, 'on_opponent_life_loss', ap, op, rand, cardDefs, odll, false, plan, plans[oi]); flushPassive(odll, 'on_opponent_life_loss', pn(ai));
            }
            const ddl = []; firePassives(ap, 'on_damage_dealt', ap, op, rand, cardDefs, ddl, false, plan, plans[oi]); flushPassive(ddl, 'on_damage_dealt', pn(ai));
            const oddl = []; firePassives(op, 'on_opponent_damage_dealt', op, ap, rand, cardDefs, oddl, false, plans[oi], plan); flushPassive(oddl, 'on_opponent_damage_dealt', pn(oi));
          }
          const discardlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'discardlink', cardDefs), combatDmg);
          if (discardlink > 0) {
            const n = Math.min(discardlink, 10);
            let discarded = 0;
            for (let _k = 0; _k < n && op.hand.length; _k++) {
              const idx = Math.floor(rand() * op.hand.length);
              arriveInDiscard(op, op.hand.splice(idx, 1)[0], cardDefs, rand, lines, 'hand', ap, plans[oi], plan);
              discarded++;
            }
            push(`  discardlink: ${pn(oi)} discarded ${discarded} card(s) (${op.hand.length} in hand)`);
          }
          // siegelink: no cost threshold -- hits every enemy structure
          // unconditionally -- see the fast-path block above for why.
          const siegelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'siegelink', cardDefs), combatDmg);
          if (siegelink > 0) {
            let hitCount = 0;
            for (const c of op.board) {
              if (!matchesCostFilter(cardDefs[c.card_id], 'structure', 'gte', 0)) continue;
              if (c.hp === null || c.hp <= 0 || isIndestructible(c, cardDefs)) continue;
              dmgCreature(c, siegelink);
              hitCount++;
            }
            push(hitCount ? `  siegelink: dealt ${siegelink} to ${hitCount} enemy structure(s)` : `  siegelink: no enemy structures to hit`);
          }
          const reliclink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'reliclink', cardDefs), combatDmg);
          if (reliclink > 0) {
            let destroyedCount = 0;
            for (const c of op.board.slice()) {
              if (c.destroyed || !matchesCostFilter(cardDefs[c.card_id], 'relic', 'lte', reliclink) || isIndestructible(c, cardDefs)) continue;
              c.destroyed = true; destroyedCount++;
              push(`  reliclink: destroyed ${cardDefs[c.card_id]?.name || c.card_id} (cost ${cardDefs[c.card_id]?.cost ?? 0})`);
              fireLeaveTrigger(c, op, ap, rand, cardDefs, lines, undefined, plans[oi], plan);
              if (!championLeave(op, c.card_id, lines, cardDefs)) toDiscardOrExile(op, c, cardDefs, rand, lines, ap, plans[oi], plan);
            }
            op.board = op.board.filter(c => !c.destroyed);
            if (!destroyedCount) push(`  reliclink: no relics matching cost filter`);
          }
          const enchantmentlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'enchantmentlink', cardDefs), combatDmg);
          if (enchantmentlink > 0) {
            let destroyedCount2 = 0;
            for (const c of op.board.slice()) {
              if (c.destroyed || !matchesCostFilter(cardDefs[c.card_id], 'enchantment', 'lte', enchantmentlink) || isIndestructible(c, cardDefs)) continue;
              c.destroyed = true; destroyedCount2++;
              push(`  enchantmentlink: destroyed ${cardDefs[c.card_id]?.name || c.card_id} (cost ${cardDefs[c.card_id]?.cost ?? 0})`);
              fireLeaveTrigger(c, op, ap, rand, cardDefs, lines, undefined, plans[oi], plan);
              if (!championLeave(op, c.card_id, lines, cardDefs)) toDiscardOrExile(op, c, cardDefs, rand, lines, ap, plans[oi], plan);
            }
            op.board = op.board.filter(c => !c.destroyed);
            if (!destroyedCount2) push(`  enchantmentlink: no enchantments matching cost filter`);
          }
          // animatelink: self-directed -- see the fast-path block above.
          const animatelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'animatelink', cardDefs), combatDmg);
          if (animatelink > 0) {
            const pool = reanimateCandidates(ap, cardDefs, '', 'creature', 'lte', animatelink);
            if (!pool.length) { push(`  animatelink: no creatures in discard matching cost filter`); }
            else {
              const cid = selectPileCard(pool, 'random', cardDefs, rand);
              const idx = ap.discard.indexOf(cid);
              if (idx !== -1) ap.discard.splice(idx, 1);
              // `lines`, not `log`: recordBattle's accumulator is named lines (see its
              // `push` helper). `log` is _simSingleGame's name for the same thing and is
              // undefined in this scope, so this threw a ReferenceError and took the
              // whole replay down the moment an animatelink creature reanimated.
              // Viewer-only path -- runCohort/hashResults never reach here, which is
              // why no validator ever noticed.
              reanimateOne(ap, op, cid, cardDefs, rand, lines, 'discard', undefined, plan, plans[oi]);
            }
          }
          // creaturelink: weakest, not random -- see the fast-path block above.
          const creaturelink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'creaturelink', cardDefs), combatDmg);
          if (creaturelink > 0) {
            const crlTs = op.board.filter(c => !c.destroyed && c.hp !== null && c.hp > 0 && matchesSupertype(cardDefs[c.card_id], 'creature') && !isUntargetable(c, cardDefs)).sort((x, y) => {
              const xs = cardDefs[x.card_id]?.stats_json ?? {};
              const ys = cardDefs[y.card_id]?.stats_json ?? {};
              return ((xs.health || 0) + (xs.armor || 0)) - ((ys.health || 0) + (ys.armor || 0));
            });
            if (!crlTs.length) { push(`  creaturelink: no damageable enemy creatures`); }
            else if (isIndestructible(crlTs[0], cardDefs)) { push(`  creaturelink: ${cardDefs[crlTs[0].card_id]?.name || crlTs[0].card_id} is indestructible — no damage`); }
            else {
              const crlAbs = dmgCreature(crlTs[0], creaturelink);
              push(`  creaturelink: dealt ${creaturelink} to weakest ${cardDefs[crlTs[0].card_id]?.name || crlTs[0].card_id}${crlAbs > 0 ? ` (${crlAbs} absorbed by armor)` : ''} → ${crlTs[0].hp} hp`);
            }
          }
          // coinlink (Avarice): see the fast-path block above. `lines`, not
          // `log` -- that name is undefined in this scope and is exactly the
          // ReferenceError animatelink shipped with (comment above).
          const coinlink = Math.min(sumCombatKWScaled(attackers, linkCapOf, 'coinlink', cardDefs), combatDmg, MAX_TOKEN_COPIES_PER_FIRE);
          if (coinlink > 0) {
            if (!cardDefs[COINLINK_TOKEN_ID]) {
              push(`  coinlink: Gold Coin (card ${COINLINK_TOKEN_ID}) is not in this payload — no coins minted`);
            } else {
              for (let _cl = 0; _cl < coinlink; _cl++) {
                reanimateOne(ap, op, COINLINK_TOKEN_ID, cardDefs, rand, lines, '', true, plan, plans[oi]);
              }
              push(`  coinlink: ${pn(ai)} minted ${coinlink} Gold Coin token(s)`);
            }
          }
          // Champion honor link
          if (ap.championId !== null) {
            const champEntry = attackers.find(e => e.card_id === ap.championId);
            if (champEntry && champEntry.melee > 0) {
              ap.honor += champEntry.melee; tally(ap, 'honor_gained', champEntry.melee);
              ap.honorGainedTotal = (ap.honorGainedTotal || 0) + champEntry.melee;
              push(`  champion honor link: ${pn(ai)} +${champEntry.melee} honor → ${ap.honor}`);
              const chgl = []; firePassives(ap, 'on_honor_gain', ap, op, rand, cardDefs, chgl, false, plan, plans[oi]); flushPassive(chgl, 'on_honor_gain', pn(ai));
              const ochgl = []; firePassives(op, 'on_opponent_honor_gain', op, ap, rand, cardDefs, ochgl, false, plans[oi], plan); flushPassive(ochgl, 'on_opponent_honor_gain', pn(oi));
            }
          }
          {
            const cll = [];
            firePassives(op, 'on_life_loss', op, ap, rand, cardDefs, cll, false, plans[oi], plan);
            // op is the defender here, not the active player -- tag explicitly so the
            // visualizer doesn't misattribute (e.g. Ice Fortress's on_life_loss shield
            // gain showing up on the attacker instead of the player who lost life).
            flushPassive(cll, 'on_life_loss', pn(oi));
            const ocll = [];
            firePassives(ap, 'on_opponent_life_loss', ap, op, rand, cardDefs, ocll, false, plan, plans[oi]);
            flushPassive(ocll, 'on_opponent_life_loss', pn(ai));
          }
          { const cdl = []; firePassives(ap, 'on_damage_dealt', ap, op, rand, cardDefs, cdl, false, plan, plans[oi]); flushPassive(cdl, 'on_damage_dealt', pn(ai)); }
          { const ocdl = []; firePassives(op, 'on_opponent_damage_dealt', op, ap, rand, cardDefs, ocdl, false, plans[oi], plan); flushPassive(ocdl, 'on_opponent_damage_dealt', pn(oi)); }
          { const prevMelee = ap.meleeDealtEvent || 0; ap.meleeDealtEvent = combatDmg;   // v4.55, see _simSingleGame
            const mdl = []; firePassives(ap, 'on_melee_damage_dealt', ap, op, rand, cardDefs, mdl, false, plan, plans[oi]); flushPassive(mdl, 'on_melee_damage_dealt', pn(ai));
            ap.meleeDealtEvent = prevMelee; }
          w = checkWin(p, turn, ht, cardDefs, lines);
          if (w) { push(`--game end--`); return finalizeTurn(w); }
        }
      }

      // 6d. After-combat triggers (v4.32) -- see _simSingleGame's copy of this
      // block for why this is a separate window from end_of_turn.
      const acLog = [];
      if (boardHasTrigger(ap.board, cardDefs, 'after_combat'))
        fireTriggers(ap.board.slice(), 'after_combat', ap, op, rand, cardDefs, acLog, plan, plans[oi]);
      if (acLog.length) {
        push(`${pn(ai)}'s permanents trigger (after combat):`);
        acLog.forEach(l => push(`  ${l}`));
      }

      // 7. Clear ephemeral energy (base pool + any restricted-energy entries
      // flagged ephemeral -- persistent restricted-energy entries carry over)
      //
      // v4.40 ephemeral_persists: the passive does not RACE this step, it
      // REPLACES it -- "becomes permanent" means that at the moment the pool
      // would have been cleared, it is banked instead. Written here and
      // nowhere else for that reason. Restricted ephemeral entries convert
      // too (owner's call): they keep their tag/supertype restriction and
      // simply stop expiring, so "your temporary energy" has no invisible
      // exception.
      if (hasPassive(ap, 'ephemeral_persists', cardDefs)) {
        if (ap.ephemeral > 0) {
          ap.energy += ap.ephemeral;
          push(`${pn(ai)}: ${ap.ephemeral} ephemeral energy becomes permanent (${ap.energy} basic)`);
          ap.ephemeral = 0;
        }
        const keptRestricted = ap.restrictedEnergy.filter(e => e.ephemeral && e.amount > 0);
        if (keptRestricted.length) {
          const total = keptRestricted.reduce((s, e) => s + e.amount, 0);
          push(`${pn(ai)}: ${total} ephemeral restricted energy becomes permanent`);
        }
        ap.restrictedEnergy = ap.restrictedEnergy.map(e => e.ephemeral ? { ...e, ephemeral: false } : e);
      } else {
      if (ap.ephemeral > 0) push(`${pn(ai)}: ${ap.ephemeral} ephemeral energy fades`);
      ap.ephemeral = 0;
      const fadedRestricted = ap.restrictedEnergy.filter(e => e.ephemeral && e.amount > 0);
      if (fadedRestricted.length) {
        const total = fadedRestricted.reduce((s, e) => s + e.amount, 0);
        push(`${pn(ai)}: ${total} ephemeral restricted energy fades`);
      }
      ap.restrictedEnergy = ap.restrictedEnergy.filter(e => !e.ephemeral);
      }

      // 8. Discard to hand limit
      let discarded = 0;
      while (ap.hand.length > MAX_HAND) {
        const idx = pickHandLimitDiscard(ap.hand, cardDefs, plan, rand);
        arriveInDiscard(ap, ap.hand.splice(idx, 1)[0], cardDefs, rand, lines, 'hand', op, plan, plans[oi]);
        discarded++;
      }
      if (discarded) push(`${pn(ai)}: discarded ${discarded} card(s) to reach hand limit`);

      push(`${pn(ai)}: turn ends  ${ap.life}hp  ${ap.honor} honor  ${ap.energy}e  hand:${ap.hand.length}  board:${ap.board.length}`);

      // Record completed turn state for visualizer
      turnData.push({
        turnNum:  Math.floor(turn / 2) + 1,
        halfTurn: turn,
        player:   ai,
        lineStart,
        lineEnd:  lines.length,
        pBefore:  snapBefore,
        pAfter:   [snapPlayer(p[0], cardDefs), snapPlayer(p[1], cardDefs)],
      });

      // Time Walk -- see the matching guard in _simSingleGame. Both loops must
      // consume the counter identically or the replay diverges from the sim.
      if (ap.extraTurns > 0) {
        ap.extraTurns--;
        lines.push(`  ${ai === 0 ? 'player1' : 'player2'} takes an additional turn`);
      } else {
        active = oi;
      }
    }

    const [p0, p1] = p;
    let result;
    if (p0.honor !== p1.honor)
      result = { winner: p0.honor > p1.honor ? 0 : 1, reason: 'timeout_honor', turns: MAX_TURNS };
    else if (p0.life !== p1.life)
      result = { winner: p0.life  > p1.life  ? 0 : 1, reason: 'timeout_life',  turns: MAX_TURNS };
    else
      result = { winner: -1, reason: 'draw', turns: MAX_TURNS };

    push(`--game end-- (${result.reason})`);
    return { lines, result, turnData, events: __events };
  }

  // ── Gzip compress/decompress (browser CompressionStream API) ─────────────────
  // compress: string → base64-encoded gzip
  async function compress(str) {
    const bytes  = new TextEncoder().encode(str);
    const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'));
    const buf    = await new Response(stream).arrayBuffer();
    const u8     = new Uint8Array(buf);
    let b64 = '';
    for (let i = 0; i < u8.length; i += 8192)
      b64 += String.fromCharCode(...u8.subarray(i, i + 8192));
    return btoa(b64);
  }

  // decompress: base64-encoded gzip → string
  async function decompress(b64) {
    const bin    = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
    const stream = new Blob([bin]).stream().pipeThrough(new DecompressionStream('gzip'));
    return await new Response(stream).text();
  }

  // ── Cohort runner with per-match logs ────────────────────────────────────────
  // Runs recordBattle for each match; returns { results, logs } where
  // logs is { match_id: lines[] }.  Use compress() on JSON.stringify(logs) before sending.
  function runCohortWithLogs(payload, onProgress) {
    const { cards, slots, matches } = payload;
    const chaosEffect   = payload.chaos_effect || null;
    const startingLife  = payload.starting_life ?? null;
    const honorToWin    = payload.honor_to_win  ?? null;
    const slotMap = normalisedSlotMap(slots);
    const results = [];
    const logs    = {};
    for (let i = 0; i < matches.length; i++) {
      const m   = matches[i];
      const sA  = slotMap[m.slot_a];
      const sB  = slotMap[m.slot_b];
      const rec = recordBattle(sA.cards, sB.cards, cards, m.seed,
                               sA.battle_plan, sB.battle_plan, undefined,
                               sA.champion_id ?? null, sB.champion_id ?? null, chaosEffect,
                               startingLife, honorToWin, slotStartOverride(sA), slotStartOverride(sB));
      results.push({
        match_id:    m.match_id,
        winner_slot: rec.result.winner === -1 ? null
                     : (rec.result.winner === 0 ? m.slot_a : m.slot_b),
        reason:      rec.result.reason,
        turns:       rec.result.turns,
      });
      logs[m.match_id] = rec.lines;
      if (onProgress) onProgress(i + 1, matches.length);
    }
    return { results, logs };
  }

  return { runCohort, runCohortWithLogs, simulateMatch, recordBattle,
           compress, decompress, hashResults, RNG, shuffle, slotStartOverride };
}));
