// Which cards does a deck actually PLAY?
//
// Static analysis cannot answer this: a card can be legal, synergistic and
// still never reach the board because its gate never opens or its cost floor
// is out of reach. This runs real matches and counts what was played.
package main

import (
	"encoding/json"
	"fmt"
	"os"

	"autobattle.online/shell/engine"
)

func main() {
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if _, _, err := engine.RunPayloadHash(string(raw)); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	// Key -> cards played. The harness maps keys back to slots with the
	// same fingerprint.
	out := map[string]interface{}{"played": engine.CensusDump()}

	// Fingerprint each slot so the caller can attribute without guessing.
	var p struct {
		Slots []struct {
			SlotID string `json:"slot_id"`
			Cards  []int  `json:"cards"`
		} `json:"slots"`
	}
	if err := json.Unmarshal(raw, &p); err == nil {
		keys := map[string]string{}
		for _, s := range p.Slots {
			keys[s.SlotID] = engine.CensusKeyFor(s.Cards)
		}
		out["slot_keys"] = keys
	}
	b, _ := json.Marshal(out)
	fmt.Println(string(b))
}
