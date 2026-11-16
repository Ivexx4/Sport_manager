import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

/** Regras partilhadas por todo o código TypeScript/React do painel. */
export default tseslint.config(
  { ignores: ["dist", "node_modules"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["**/*.{ts,tsx}"],
    plugins: { "react-hooks": reactHooks },
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      parserOptions: { ecmaFeatures: { jsx: true } }
    },
    // Regras clássicas do react-hooks: as regras do React Compiler (set-state-in-effect, purity,
    // immutability, ...) ficam de fora porque este painel carrega dados com setState dentro de
    // useEffect, um padrão que as regras novas assinalam e que não se muda nesta alteração.
    rules: {
      "react-hooks/rules-of-hooks": "error",
      "react-hooks/exhaustive-deps": "warn"
    }
  }
);
