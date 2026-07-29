// Proves the mesh chat's context trimming against the real app source.
//
// This is the piece most able to be quietly wrong. A mesh conversation is assembled here and sent
// as ONE prompt — there is no server-side history — so if trimming drops the wrong end, or reports
// a different number of turns than it actually left out, the symptom on a phone is a model that
// "forgot", with nothing to distinguish it from a broken link. Getting that wrong would send us
// hunting the network for a bug that lives in a loop over strings.
//
//   node integration/chatterui_llamacpp/js/context_smoke.mjs

import { buildBranchPrompt, estimateTokens } from '../../../ChatterUI/lib/helixPrompt.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }

const turn = (role, text) => ({ role, text, at: 0 })

// --- a conversation well inside the budget keeps every turn ---
{
  const turns = [
    turn('user', 'my name is Ada'),
    turn('assistant', 'Hello Ada.'),
    turn('user', 'what is my name?'),
  ]
  const built = buildBranchPrompt(turns, 8192)
  check(built.dropped === 0, 'nothing is dropped when the history fits')
  check(built.prompt.includes('my name is Ada'), 'the first turn survives — this is what context MEANS')
  check(built.prompt.includes('User: what is my name?'), 'the latest turn is present')
  check(built.prompt.endsWith('\nAssistant:'), 'the prompt ends by handing the turn to the model')
}

// --- roles are labelled, so the model can tell who said what ---
{
  const built = buildBranchPrompt([turn('user', 'hi'), turn('assistant', 'hello')], 8192)
  check(built.prompt.includes('User: hi'), 'user turns are labelled')
  check(built.prompt.includes('Assistant: hello'), 'assistant turns are labelled')
}

// --- over budget: the OLDEST go, never the newest ---
{
  const turns = []
  for (let i = 0; i < 40; i++) turns.push(turn(i % 2 ? 'assistant' : 'user', `line ${i} ` + 'x'.repeat(200)))
  // 1024 budget less the 512 reserved for the answer leaves ~512 tokens ≈ 2048 characters.
  const built = buildBranchPrompt(turns, 1024)
  check(built.dropped > 0, 'a history larger than the budget does drop turns')
  check(built.prompt.includes('line 39'), 'the most recent turn is always kept')
  check(!built.prompt.includes('line 0 '), 'the oldest turn is the one dropped')
  check(
    built.dropped === turns.length - (built.prompt.split('\n').length - 1),
    'the reported drop count matches the lines actually kept'
  )
}

// --- the budget is respected, with room left for the answer ---
{
  const turns = []
  for (let i = 0; i < 200; i++) turns.push(turn('user', `line ${i} ` + 'y'.repeat(100)))
  const budget = 2048
  const built = buildBranchPrompt(turns, budget)
  check(built.estimatedTokens <= budget - 512, 'the assembled prompt stays under budget minus the answer reserve')
  check(built.estimatedTokens === estimateTokens(built.prompt), 'the reported size is the size of what was built')
}

// --- one turn bigger than the whole budget still gets sent ---
{
  const huge = turn('user', 'z'.repeat(100000))
  const built = buildBranchPrompt([huge], 1024)
  check(built.prompt.includes('zzz'), 'an oversized single turn is sent rather than dropped into silence')
  check(built.dropped === 0, 'and it is not reported as dropped, because it was not')
}

// --- an empty branch is a valid prompt, not a crash ---
{
  const built = buildBranchPrompt([], 4096)
  check(built.dropped === 0, 'an empty branch drops nothing')
  check(built.prompt.trim() === 'Assistant:', 'an empty branch is just the model being handed the turn')
}

// --- a tiny budget is floored rather than producing nothing ---
{
  const built = buildBranchPrompt([turn('user', 'hello there')], 16)
  check(built.prompt.includes('hello there'), 'an absurdly small budget still sends the newest turn')
}

console.log(`\nALL PASSED (${pass} checks)`)
