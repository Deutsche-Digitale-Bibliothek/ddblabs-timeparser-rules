// Run with `node scripts/check-live-validation.cjs`; no additional packages are required.
// Execute the actual inline script against a small DOM substitute to check input feedback.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');

const template = fs.readFileSync(path.join(__dirname, '../src/main/resources/templates/rules/detail.html'), 'utf8');
const script = template.match(/<script>\s*([\s\S]*?)<\/script>/)[1];
new vm.Script(script, {filename: 'detail.html inline script'});

function form(values = {}) {
    const listeners = new Map();
    let section;
    function input(value) {
        const classes = new Set();
        const element = {
            value, dataset: {},
            classList: {add: (name) => classes.add(name), remove: (name) => classes.delete(name), contains: (name) => classes.has(name)},
            parentElement: {querySelector: () => element.feedback || null},
            insertAdjacentElement: (_where, feedback) => { element.feedback = feedback; },
            closest: () => section
        };
        return element;
    }
    const fields = Object.fromEntries(Object.entries({
        inputMask: '####', inputPattern: 'JJJJ', outputMask: '####', outputPattern: 'JJJJ',
        input: '1900', tokenized: '1900', output: '1900', timespan: '', ...values
    }).map(([key, value]) => [key, input(value)]));
    const selectors = {
        '[data-rule-input-mask]': 'inputMask', '[data-rule-input-pattern]': 'inputPattern',
        '[data-rule-output-mask]': 'outputMask', '[data-rule-output-pattern]': 'outputPattern',
        '[data-test-input]': 'input', '[data-test-tokenized]': 'tokenized', '[data-test-output]': 'output', '[data-test-timespan]': 'timespan'
    };
    const row = {querySelector: (selector) => fields[selectors[selector]] || null};
    section = {
        querySelector: (selector) => fields[selectors[selector]] || null,
        querySelectorAll: (selector) => selector === '[data-test-row]' ? [row] : []
    };
    const document = {
        querySelector: () => null,
        querySelectorAll: (selector) => selector === '[data-rule-section]' ? [section] : [],
        createElement: () => ({dataset: {}, textContent: ''}),
        addEventListener: (name, callback) => listeners.set(name, callback)
    };
    vm.runInNewContext(script, {document});
    return {
        fields,
        message: (name) => fields[name].feedback?.textContent || '',
        invalid: (name) => fields[name].classList.contains('is-invalid'),
        change: (name, value) => { fields[name].value = value; listeners.get('input')({target: fields[name]}); }
    };
}

let cases = 0;
function test(name, action) { action(); cases++; process.stdout.write('PASS ' + name + '\n'); }

test('R344 immediately diagnoses the dash and clears after explicit correction', () => {
    const page = form({inputMask: '####, #### – ####', inputPattern: 'JJJJ, XXXX – ZZZZ',
        tokenized: '1945, 1947 - 1949', outputMask: '####/####', outputPattern: 'JJJJ/ZZZZ', output: '1945/1949'});
    assert.equal(page.invalid('tokenized'), true);
    assert.match(page.message('tokenized'), /Position 12/);
    assert.match(page.message('tokenized'), /Erwartet: \[–\] \(U\+2013\), vorhanden: \[-\] \(U\+002D\)/);
    assert.equal(page.invalid('inputPattern'), false);
    page.change('tokenized', '1945, 1947 – 1949');
    assert.equal(page.invalid('tokenized'), false);
    assert.equal(page.message('tokenized'), '');
    assert.equal(page.fields.tokenized.dataset.liveInvalid, undefined);
});

test('mask/pattern literals are checked immediately and clear after correction', () => {
    const page = form({inputMask: '#### – ####', inputPattern: 'JJJJ - ZZZZ', tokenized: '1900 – 2000'});
    assert.match(page.message('inputPattern'), /Position 6.*\[–\].*U\+2013.*\[-\].*U\+002D/);
    page.change('inputPattern', 'JJJJ – ZZZZ');
    assert.equal(page.invalid('inputPattern'), false);
    assert.equal(page.message('inputPattern'), '');
});

test('output pattern literals are compared without interpreting output test values literally', () => {
    const page = form({outputMask: '####-MM', outputPattern: 'JJJJ/NN', output: '2010-03'});
    assert.match(page.message('outputPattern'), /Position 5.*\[-\].*U\+002D.*\[\/\].*U\+002F/);
    page.change('outputPattern', 'JJJJ-NN');
    assert.equal(page.invalid('outputPattern'), false);
    assert.equal(page.invalid('output'), false);
});

for (const [expected, actual, expectedDisplay, actualDisplay] of [
    [' ', '\u00A0', '[␠] (U+0020)', '[⍽] (U+00A0)'],
    ['\u00A0', '\u202F', '[⍽] (U+00A0)', '[⎵] (U+202F)'],
    ['\t', ' ', '[⇥] (U+0009)', '[␠] (U+0020)'],
    ['\r', '\n', '[␍] (U+000D)', '[␊] (U+000A)']
]) {
    test('visible whitespace diagnostic ' + expectedDisplay + ' against ' + actualDisplay, () => {
        const page = form({inputMask: '####' + expected + 'x', inputPattern: 'JJJJ' + expected + 'x', tokenized: '1900' + actual + 'x'});
        assert.ok(page.message('tokenized').includes('Erwartet: ' + expectedDisplay + ', vorhanden: ' + actualDisplay));
    });
}

test('emoji diagnostics use full codepoints when the low surrogate differs', () => {
    const page = form({inputMask: '🌸####', inputPattern: '🌸JJJJ', tokenized: '🌷1900'});
    assert.match(page.message('tokenized'), /Position 2/);
    assert.ok(page.message('tokenized').includes('[🌸] (U+1F338)'));
    assert.ok(page.message('tokenized').includes('[🌷] (U+1F337)'));
});

test('pattern emoji diagnostics also use complete codepoints', () => {
    const page = form({inputMask: '🌸####', inputPattern: '🌷JJJJ', tokenized: '🌸1900'});
    assert.ok(page.message('inputPattern').includes('[🌸] (U+1F338)'));
    assert.ok(page.message('inputPattern').includes('[🌷] (U+1F337)'));
});

for (const [expected, actual, expectedCode, actualCode] of [
    ['\u0001', '\u0085', 'U+0001', 'U+0085'],
    ['\u200B', '\u200E', 'U+200B', 'U+200E']
]) {
    test('invisible control/format characters have visible labels and codepoints ' + expectedCode, () => {
        const page = form({inputMask: '####' + expected + 'x', inputPattern: 'JJJJ' + expected + 'x', tokenized: '1900' + actual + 'x'});
        assert.ok(page.message('tokenized').includes('Erwartet: [unsichtbares Zeichen] (' + expectedCode + ')'));
        assert.ok(page.message('tokenized').includes('vorhanden: [unsichtbares Zeichen] (' + actualCode + ')'));
    });
}

for (const invalidDigit of ['x', '١', 'Ｑ']) {
    test('hash positions reject non-ASCII digit ' + invalidDigit, () => {
        const page = form({tokenized: invalidDigit + '900'});
        assert.match(page.message('tokenized'), /eine ASCII-Ziffer \[0–9\]/);
        page.change('tokenized', '1900');
        assert.equal(page.invalid('tokenized'), false);
    });
}

test('month and weekday mask variables retain their semantics', () => {
    const page = form({inputMask: 'GG MM ####', inputPattern: 'WW NN JJJJ', tokenized: 'GG MM 2010',
        outputMask: '####-MM', outputPattern: 'JJJJ-NN', output: '2010-03'});
    assert.equal(page.invalid('inputPattern'), false);
    assert.equal(page.invalid('tokenized'), false);
    assert.equal(page.invalid('outputPattern'), false);
    assert.equal(page.invalid('output'), false);
});

test('split month variable names are diagnosed as variables, not literals', () => {
    const page = form({inputMask: 'MM ####', inputPattern: 'NT JJJJ', tokenized: 'MM 2010'});
    assert.match(page.message('inputPattern'), /zwei gleiche Variablenbuchstaben/);
});

test('unexpanded tokens are left for server expansion', () => {
    const page = form({inputMask: '#### ~separator ####', inputPattern: 'JJJJ ~separator ZZZZ', tokenized: '1900 - 2000'});
    assert.equal(page.invalid('inputPattern'), false);
    assert.equal(page.invalid('tokenized'), false);
});

test('digit templates are filled from the raw input without changing either field', () => {
    const page = form({input: '1900', tokenized: '####'});
    assert.equal(page.invalid('tokenized'), false);
    assert.equal(page.fields.tokenized.value, '####');
    assert.equal(page.fields.input.value, '1900');
    page.change('input', 'abcd');
    assert.equal(page.invalid('tokenized'), true);
    assert.ok(page.message('tokenized').includes('[#] (U+0023)'));
    page.change('input', '1950');
    assert.equal(page.invalid('tokenized'), false);
});

test('mixed fixed digits and digit placeholders are accepted', () => {
    const page = form({input: '1900', tokenized: '19##'});
    assert.equal(page.invalid('tokenized'), false);
});

test('raw-input literal differences prevent partial template filling', () => {
    const page = form({inputMask: '####-####', inputPattern: 'JJJJ-ZZZZ',
        input: '1900–2000', tokenized: '####-####'});
    assert.equal(page.invalid('tokenized'), true);
    assert.ok(page.message('tokenized').includes('Position 1'));
    assert.ok(page.message('tokenized').includes('[#] (U+0023)'));
    page.change('input', '1900-2000');
    assert.equal(page.invalid('tokenized'), false);
});

test('a digit template cannot bypass a literal mask position', () => {
    const page = form({inputMask: '####x', inputPattern: 'JJJJx', input: '19001', tokenized: '1900#'});
    assert.equal(page.invalid('tokenized'), true);
    assert.match(page.message('tokenized'), /Position 5.*\[x\].*U\+0078.*\[1\].*U\+0031/);
});

test('Unicode decimal digits are filled but still rejected by the ASCII mask validator', () => {
    const page = form({input: '١900', tokenized: '####'});
    assert.equal(page.invalid('tokenized'), true);
    assert.ok(page.message('tokenized').includes('eine ASCII-Ziffer [0–9]'));
    assert.ok(page.message('tokenized').includes('[١] (U+0661)'));
});

test('raw-input token expansion is deferred before filling digit templates', () => {
    const page = form({input: '~year', tokenized: '####'});
    assert.equal(page.invalid('tokenized'), false);
});

test('length checks remain active', () => {
    const page = form({tokenized: '19000', output: '190'});
    assert.match(page.message('tokenized'), /gleich lang/);
    assert.match(page.message('output'), /gleich lang/);
});

process.stdout.write('Passed ' + cases + ' live-validation cases.\n');
