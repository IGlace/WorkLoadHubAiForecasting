import js from '@eslint/js'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['out/**', 'dist/**', 'node_modules/**', 'resources/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  { files: ['src/main/**/*.ts', 'src/preload/**/*.ts'], languageOptions: { globals: globals.node } },
  { files: ['src/renderer/**/*.{ts,tsx}'], languageOptions: { globals: globals.browser } },
  { files: ['scripts/**/*.mjs', 'eslint.config.js'], languageOptions: { globals: globals.node } },
)
