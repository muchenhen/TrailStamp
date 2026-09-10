import fs from 'node:fs'

fs.writeFileSync('data/china.json', '{"fixture":"mutated-before-failure"}\n')
console.error('intentional rollback fixture failure')
process.exit(1)
