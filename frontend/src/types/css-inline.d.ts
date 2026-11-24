// `import css from './X.css?inline'` → CSS 내용을 문자열로 받는다.
// next.config.ts의 turbopack.rules(`?inline` 쿼리 → type: 'raw')와 짝을 이룬다.
declare module '*.css?inline' {
  const css: string;
  export default css;
}
