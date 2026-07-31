// Proves the mesh chat's context trimming against the real app source.
//
// This is the piece most able to be quietly wrong. A mesh conversation is assembled here and sent
// as ONE prompt — there is no server-side history — so if trimming drops the wrong end, or reports
// a different number of turns than it actually left out, the symptom on a phone is a model that
// "forgot", with nothing to distinguish it from a broken link. Getting that wrong would send us
// hunting the network for a bug that lives in a loop over strings.
//
//   node integration/chatterui_llamacpp/js/context_smoke.mjs

import {
  buildBranchPrompt,
  estimateTokens,
  MESH_PREAMBLE,
  MESH_STOP_SEQUENCES,
  buildBranchMessages,
  trimAtStopSequence,
} from '../../../ChatterUI/lib/helixPrompt.ts'

// Turn lines only — the preamble is prose, not a turn, and counting it as one would make every
// drop-count assertion below off by however many lines it happens to occupy.
const turnLines = (prompt) =>
  prompt
    .slice(MESH_PREAMBLE.length)
    .split('\n')
    .filter((l) => l.startsWith('User:') || l.startsWith('Assistant: ')).length

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
    built.dropped === turns.length - turnLines(built.prompt),
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
  check(
    built.prompt.trim() === `${MESH_PREAMBLE}\n\n\nAssistant:`.trim(),
    'an empty branch is the lead-in plus the model being handed the turn'
  )
}

// --- a tiny budget is floored rather than producing nothing ---
{
  const built = buildBranchPrompt([turn('user', 'hello there')], 16)
  check(built.prompt.includes('hello there'), 'an absurdly small budget still sends the newest turn')
}

// --- the lead-in, which is what stops an answer running on into the next turn ---
{
  const built = buildBranchPrompt([turn('user', 'hi')], 8192)
  check(built.prompt.startsWith(MESH_PREAMBLE), 'every prompt opens with the lead-in')
  check(
    /replies\s+once/.test(MESH_PREAMBLE) && /never writes the user/.test(MESH_PREAMBLE),
    'the lead-in actually says to answer once and not continue the conversation'
  )
  // Budgeting has to include it, or the prompt goes over by exactly the preamble every time.
  const turns = []
  for (let i = 0; i < 200; i++) turns.push(turn('user', `line ${i} ` + 'q'.repeat(100)))
  const budget = 2048
  const big = buildBranchPrompt(turns, budget)
  check(
    big.estimatedTokens <= budget - 512,
    'the lead-in is counted against the budget, not added on top of it'
  )
}

// --- trimming, for answers that ran on anyway ---
{
  check(
    trimAtStopSequence('The sky is blue.\nUser: and grass?\nAssistant: green') === 'The sky is blue.',
    'an answer that writes the next turn is cut at the point it starts'
  )
  check(
    trimAtStopSequence('  A plain answer.  ') === 'A plain answer.',
    'an answer that behaved is returned whole, just trimmed of space'
  )
  check(trimAtStopSequence('') === '', 'an empty answer stays empty rather than throwing')
  check(
    MESH_STOP_SEQUENCES.includes('\nUser:'),
    'the newline form is among the stop sequences — it is the one that actually fires'
  )
  // The pathological case from the device: the model repeating the whole exchange back.
  const runOn =
    'Зонтик — это растение.\nUser: Цветок зонтик\nAssistant: Зонтик — это растение.\nUser: Цветок зонтик'
  check(
    trimAtStopSequence(runOn) === 'Зонтик — это растение.',
    'the repeated-transcript answer seen on device is cut back to the one real reply'
  )
}

// --- messages, which is what an instruct model actually expects ---
//
// Handed the flat transcript instead, Qwen3.5 answered by commenting on the input: "I am confused
// by the structure of the input provided in the `user` message block". These assertions are about
// the shape that stops that happening.
{
  const turns = [
    turn('user', 'my name is Ada'),
    turn('assistant', 'Hello Ada.'),
    turn('user', 'what is my name?'),
  ]
  const built = buildBranchMessages(turns, 8192)
  check(built.messages[0].role === 'system', 'the lead-in is a system message, not glued to a user turn')
  check(built.messages[0].content === MESH_PREAMBLE, 'and it is the same lead-in')
  check(built.messages.length === turns.length + 1, 'every turn that fits becomes its own message')
  check(
    built.messages[1].role === 'user' && built.messages[1].content === 'my name is Ada',
    'a turn carries its own text, with no "User:" prefix for the model to puzzle over'
  )
  check(
    built.messages[built.messages.length - 1].role === 'user',
    'the conversation ends on the user, so the template asks for an assistant reply'
  )
  check(built.dropped === 0, 'nothing is dropped when it all fits')
}

// --- a conversation must never open on an assistant turn ---
{
  // What a forked branch or an aggressive trim can easily produce.
  const built = buildBranchMessages([turn('assistant', 'orphaned'), turn('user', 'hi')], 8192)
  const roles = built.messages.slice(1).map((m) => m.role)
  check(roles[0] === 'user', 'a leading assistant turn is dropped rather than sent to a strict template')
}

// --- trimming still drops the oldest first ---
{
  const turns = []
  for (let i = 0; i < 60; i++) turns.push(turn(i % 2 ? 'assistant' : 'user', `line ${i} ` + 'x'.repeat(200)))
  const built = buildBranchMessages(turns, 1024)
  check(built.dropped > 0, 'messages over budget do drop turns')
  const joined = built.messages.map((m) => m.content).join('\n')
  check(joined.includes('line 59'), 'the newest turn survives')
  check(!joined.includes('line 0 '), 'the oldest turn is the one dropped')
}

console.log(`\nALL PASSED (${pass} checks)`)