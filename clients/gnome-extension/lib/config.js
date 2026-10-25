// Public installed-client configuration ported from the Android client.
// These values are provider-distributed application configuration, not user sessions.
export const UUID = 'quotadeck@juliendevelopment.github.io';
export const VERSION = '0.1.0';
export const PROVIDERS = Object.freeze({
    CLAUDE: {title: 'Claude', home: 'https://claude.ai/settings/usage'},
    CODEX: {title: 'Codex', home: 'https://chatgpt.com/codex/settings/usage'},
    ANTIGRAVITY: {title: 'Antigravity', home: 'https://antigravity.google/'},
    OPENCODE: {title: 'OpenCode Go', home: 'https://opencode.ai/workspace'},
});
export const IDS = Object.keys(PROVIDERS);
export const OAUTH = Object.freeze({
    claudeClient: '9d1c250a-e61b-44d9-88ed-5944d1962f5e',
    openaiClient: 'app_EMoamEEZ73f0CkXaXp7hrann',
    googleClient: '1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com',
    googleInstalledValue: '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',
    googleScopes: [
        'https://www.googleapis.com/auth/cloud-platform',
        'https://www.googleapis.com/auth/userinfo.email',
        'https://www.googleapis.com/auth/userinfo.profile',
        'https://www.googleapis.com/auth/cclog',
        'https://www.googleapis.com/auth/experimentsandconfigs',
    ],
});
// Only these fixed destinations can receive credentials. No user-supplied URLs.
export const ENDPOINTS = Object.freeze({
    claudeAuthorize: 'https://claude.com/cai/oauth/authorize',
    claudeToken: 'https://platform.claude.com/v1/oauth/token',
    claudeUsage: 'https://api.anthropic.com/api/oauth/usage',
    codexStart: 'https://auth.openai.com/api/accounts/deviceauth/usercode',
    codexPoll: 'https://auth.openai.com/api/accounts/deviceauth/token',
    codexToken: 'https://auth.openai.com/oauth/token',
    codexUsage: 'https://chatgpt.com/backend-api/wham/usage',
    googleAuthorize: 'https://accounts.google.com/o/oauth2/v2/auth',
    googleToken: 'https://oauth2.googleapis.com/token',
    googleLoad: 'https://daily-cloudcode-pa.googleapis.com/v1internal:loadCodeAssist',
    googleSummary: 'https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary',
    googleModels: 'https://daily-cloudcode-pa.googleapis.com/v1internal:fetchAvailableModels',
    opencodeUsage: 'https://opencode.ai/zen/go/v1/usage',
});
export function requireProvider(id) {
    if (!IDS.includes(id))
        throw new Error('Invalid provider');
    return id;
}
