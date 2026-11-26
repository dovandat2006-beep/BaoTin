import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'./tests/polish',workers:2,outputDir:'.redesign-artifacts/shell-polish-20261113/results',use:{baseURL:'http://127.0.0.1:4192'},projects:[320,375,414,768,1440].flatMap(width=>(['light','dark'] as const).map(colorScheme=>({name:`${width}-${colorScheme}`,use:{viewport:{width,height:1000},colorScheme}})))});
