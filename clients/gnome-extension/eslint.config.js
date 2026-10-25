export default [
    {ignores: ['dist/**', 'node_modules/**', 'build/**']},
    {files: ['tests/**/*.js'], languageOptions: {globals: {URL: 'readonly'}}},
    {
        files: ['**/*.js'],
        languageOptions: {
            ecmaVersion: 2022,
            sourceType: 'module',
            globals: Object.fromEntries(['console', 'TextDecoder', 'TextEncoder', 'ARGV', 'print', 'process', 'Buffer', 'setImmediate', 'setTimeout', 'clearTimeout'].map(k => [k, 'readonly'])),
        },
        rules: {
            'no-unused-vars': ['error', {argsIgnorePattern: '^_', caughtErrors: 'none'}],
            'no-undef': 'error',
            'no-unreachable': 'error',
            'no-constant-condition': ['error', {checkLoops: false}],
            'eqeqeq': 'error',
        },
    },
];
