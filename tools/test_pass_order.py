"""Behaviour test for optimise(first=...): which pass gets the round."""
import sys, collections
sys.path.insert(0, '/home/hhkk/Documents/autobattle-agent')

import abagent.moves as moves
import abagent.search as search
from abagent.harness import Score

CARDS = [1, 1, 2, 3]
SWAPPED = [1, 1, 2, 99]


def mkscore(by_seed):
    return Score(wins=sum(by_seed.values()) / len(by_seed), losses=0.0, draws=0.0,
                 wins_sd=0.5, n_seeds=len(by_seed), n_opponents=4,
                 per_opponent={}, wins_by_seed=dict(by_seed))


class Clock:
    def __init__(self, t=1000.0): self.t = t
    def time(self): return self.t


class FakeHarness:
    """Records which pass asked for scoring; optionally lets one candidate win."""
    def __init__(self, clock, cost=0.0, winner=False):
        self.clock, self.cost, self.winner = clock, cost, winner
        self.passes = []

    def evaluate(self, batch, opponents, block):
        base_cards = dict((l, c) for l, c, p in batch)['__base__'] if any(
            l == '__base__' for l, c, p in batch) else None
        if base_cards is not None:                       # stage one: the screen
            kind = 'swap' if any(c != base_cards for l, c, p in batch
                                 if l != '__base__') else 'order'
            self.passes.append(kind)
        self.clock.t += self.cost
        out = {}
        for i, (lbl, c, p) in enumerate(batch):
            if lbl in ('__base__', 'keep'):
                out[lbl] = mkscore({s: 10 for s in block})
            elif self.winner:
                out[lbl] = mkscore({s: 11 for s in block})   # +1W on every seed
            else:
                out[lbl] = mkscore({s: 9 for s in block})    # never ahead
        return out


def install_fakes():
    moves.order_moves = lambda plan, rng, n: [{"card_order": [2, 1]}]
    moves.add_candidates = lambda mine, present, copies: [(99, 1)]
    moves.cut_candidates = lambda mine, present, plan: [3]
    moves.swap_ranks = lambda n: [0]
    moves.swap = lambda cards, plan, cut, add, qty, limit, rank=0: (
        list(SWAPPED), dict(plan))
    moves.as_counter = lambda cards: collections.Counter(cards)
    moves.field_signal = lambda meta: ({}, {})


def run(first, cost=0.0, deadline=None, winner=False):
    install_fakes()
    clock = Clock()
    search.time = clock
    h = FakeHarness(clock, cost=cost, winner=winner)
    logs = []
    cards, plan, changes = search.optimise(
        h, list(CARDS), [], [], {1: {}, 2: {}, 3: {}, 99: {"deck_limit": 1}},
        plan={"card_order": [1, 2]}, rounds=1, first=first,
        deadline=(clock.t + deadline) if deadline else None,
        log=logs.append)
    return h.passes, cards, plan, changes, logs


fails = []
def check(name, cond, detail=""):
    print(("  ok   " if cond else "  FAIL ") + name + ("" if cond else f"  <- {detail}"))
    if not cond: fails.append(name)

print("1. pass order")
p, *_ = run("order");  check("first='order' runs order then swap", p == ["order", "swap"], p)
p, *_ = run("swap");   check("first='swap' runs swap then order",  p == ["swap", "order"], p)

print("2. the deadline between passes kills the SECOND pass, whichever it is")
p, _, _, _, logs = run("swap", cost=100.0, deadline=50.0)
check("swap-first: only swap ran", p == ["swap"], p)
check("log names swap", any("deadline reached after swap" in l for l in logs), logs)
p, _, _, _, logs = run("order", cost=100.0, deadline=50.0)
check("order-first: only order ran", p == ["order"], p)
check("historical wording kept", any("deadline reached after order" in l for l in logs), logs)

print("3. nonlocal rebinding actually takes (the refactor's real risk)")
p, cards, plan, changes, logs = run("swap", winner=True)
check("a confirmed swap rebinds cards", cards == SWAPPED, cards)
check("the swap is recorded", any("+1" in c for c in changes), changes)
p, cards, plan, changes, logs = run("order", winner=True)
check("a confirmed order rebinds plan", plan.get("card_order") == [2, 1], plan)
check("the order change is recorded", any(c.startswith("order") for c in changes), changes)

print("4. nothing confirmed still stops the round")
p, _, _, changes, logs = run("order")
check("both passes ran, no changes", changes == [] and p == ["order", "swap"], (p, changes))
check("says so", any("nothing confirmed" in l for l in logs), logs)

print("5. a bad value is refused, not silently defaulted")
try:
    run("bogus"); check("ValueError on bad first", False, "no raise")
except ValueError as e:
    check("ValueError on bad first", "bogus" in str(e), str(e))

print(f"\n{len(fails)} failure(s)" if fails else "\nall assertions pass")
sys.exit(1 if fails else 0)
