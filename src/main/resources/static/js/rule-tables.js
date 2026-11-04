/**
 * Gemeinsame DataTables-Einstellungen der serverseitig gerenderten Listen.
 *
 * data-table-language="full" ergänzt die bisherige ausführliche Übersetzung.
 * data-action-column bezeichnet die nullbasierte Aktionsspalte, die weder
 * sortiert noch durchsucht wird. Ohne Attribut bleiben alle Spalten verfügbar.
 * Die Initialisierung am Seitenende berücksichtigt nur tatsächlich vorhandene
 * Tabellen; ein leerer Plausibilitätsbericht benötigt deshalb keinen Sonderpfad.
 */
(() => {
    const language = {
        search: 'Suchen:',
        lengthMenu: '_MENU_ Einträge pro Seite',
        lengthLabels: {'-1': 'Alle'},
        info: '_START_ bis _END_ von _TOTAL_ Einträgen',
        infoEmpty: 'Keine Einträge vorhanden',
        infoFiltered: '(gefiltert aus _MAX_ Einträgen)',
        zeroRecords: 'Keine passenden Einträge gefunden',
        emptyTable: 'Keine Einträge vorhanden',
        paginate: {
            first: 'Erste',
            previous: 'Zurück',
            next: 'Weiter',
            last: 'Letzte'
        }
    };
    const fullLanguage = {
        thousands: '.',
        loadingRecords: 'Wird geladen...',
        processing: 'Verarbeitung läuft...',
        aria: {
            orderable: 'Sortierung für diese Spalte aktivieren',
            orderableReverse: 'Umgekehrte Sortierung für diese Spalte aktivieren'
        }
    };

    document.querySelectorAll('table[data-rule-table]').forEach((table) => {
        const options = {
            pageLength: 50,
            lengthMenu: [50, 100, 250, 1000, {label: 'Alle', value: -1}],
            language: table.dataset.tableLanguage === 'full' ? {...language, ...fullLanguage} : {...language}
        };
        if (table.dataset.actionColumn !== undefined) {
            options.columnDefs = [{orderable: false, searchable: false, targets: Number(table.dataset.actionColumn)}];
        }
        new DataTable('#' + table.id, options);
    });
})();
