import EmbeddedPostgres from 'embedded-postgres';

const pg = new EmbeddedPostgres({
    databaseDir: './data/db',
    user: 'retryx',
    password: 'retryx',
    port: 5432,
    persistent: true,
    initdbFlags: ['-E', 'UTF8', '--locale=C']
});

async function run() {
    try {
        console.log('Initializing embedded postgres cluster with UTF-8...');
        await pg.initialise();
    } catch (e) {
        console.log('Cluster initialization notice:', e.message);
    }
    
    console.log('Starting postgres on port 5432...');
    await pg.start();
    
    try {
        console.log('Creating database retryx with UTF-8 encoding...');
        await pg.createDatabase('retryx');
    } catch (e) {
        console.log('Database notice:', e.message);
    }
    console.log('PostgreSQL (UTF-8) is ready and accepting connections on port 5432!');
}

run().catch(err => {
    console.error('Failed to run embedded postgres:', err);
    process.exit(1);
});
