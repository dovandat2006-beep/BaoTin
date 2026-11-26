import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests/shell',workers:2,outputDir:'.redesign-artifacts/app-shell-20261113/results',use:{baseURL:'http://127.0.0.1:4188'},projects:[320,375,414,600,768,1440,1920].flatMap(width=>(['light','dark'] as const).map(colorScheme=>({name:`${width}-${colorScheme}`,use:{viewport:{width,height:1000},colorScheme}})))});
