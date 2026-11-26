import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests/topbar',workers:2,outputDir:'.redesign-artifacts/topbar-20261113/results',use:{baseURL:'http://127.0.0.1:4187'},projects:[320,375,414,768,1440].flatMap(width=>(['light','dark'] as const).map(colorScheme=>({name:`${width}-${colorScheme}`,use:{viewport:{width,height:900},colorScheme}})))});
