import * as Parse from './parsers.js';
import {Failure} from './errors.js';

export class Providers {
    constructor(http, now = Date.now) {
        this.http = http;
        this.now = now;
    }

    async fetch(id, credential, scope) {
        const headers = {Authorization: `Bearer ${credential.accessToken}`};
        if (id === 'CLAUDE') {
            const data = await this.http.request('claudeUsage', {headers: {...headers, 'anthropic-beta': 'oauth-2025-04-20'}}, scope);
            return {credential, snapshot: Parse.claude(data, this.now())};
        }
        if (id === 'CODEX') {
            if (credential.accountId)
                headers['ChatGPT-Account-Id'] = credential.accountId;
            const data = await this.http.request('codexUsage', {headers}, scope);
            return {credential, snapshot: Parse.codex(data, this.now())};
        }
        if (id === 'OPENCODE') {
            const data = await this.http.request('opencodeUsage', {headers}, scope);
            return {credential, snapshot: Parse.opencode(data, this.now())};
        }
        if (id !== 'ANTIGRAVITY')
            throw new Failure();
        headers['User-Agent'] = 'antigravity/hub/2.24.0 linux/amd64';
        const info = await this.http.request('googleLoad', {headers, body: {
            metadata: {ideType: 'ANTIGRAVITY', platform: 'PLATFORM_UNSPECIFIED', pluginType: 'GEMINI'},
        }}, scope);
        if (Array.isArray(info?.ineligibleTiers) && info.ineligibleTiers.some(t => t?.reasonCode === 'GOOGLE_TOS_NOT_SUPPORTED_BY_CLIENT') && !Parse.object(info.currentTier))
            throw new Failure('CLIENT');
        const project = Parse.text(info?.cloudaicompanionProject) ?? Parse.text(info?.cloudaicompanionProject?.id) ??
            Parse.text(info?.cloudaicompanionProject?.projectId) ?? Parse.text(credential.projectId);
        if (!project)
            throw new Failure('INITIALIZE');
        const body = {project};
        let summary = null;
        try {
            summary = await this.http.request('googleSummary', {headers, body}, scope);
        } catch (error) {
            scope.check();
            // Do not bypass client refusals or rate limits via fallback requests.
            if (!(error instanceof Failure) || ![404, 405, 501].includes(error.http))
                throw error;
        }
        const models = Parse.antigravitySummary(summary).length ? null : await this.http.request('googleModels', {headers, body}, scope);
        const plan = Parse.text(info?.planInfo?.planType) ?? Parse.text(info?.paidTier?.name) ?? Parse.text(info?.currentTier?.name);
        return {credential: {...credential, projectId: project},
            snapshot: Parse.antigravity(summary, models, plan, this.now())};
    }
}
