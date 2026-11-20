// Only the canonical confirmation for a fresh test-fixture pack may be clicked.
export function fixturePackInPrompt(prompt, expectedIds) {
  const lines=prompt.split('\n').map(line=>line.trim());
  for(const exact of [
    'Service: awai.fs',
    'Operation: download',
    'Endpoint: https://yataverse.com',
  ]){
    if(lines.filter(line=>line===exact).length!==1)return undefined;
  }
  const action=lines.filter(line=>line.startsWith('Action: '));
  if(action.length!==1)return undefined;
  const id=/^Action: GET \/awai-drive\/v1\/packs\/([a-f0-9]{64})$/.exec(action[0])?.[1];
  if(id&&expectedIds.has(id))return id;
}
