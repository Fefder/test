// Ялинка розміру n: n рядів крони з '*', потім один центрований ряд стовбура '|'.
// Введення: рівно одне додатне десяткове ціле число через stdin до EOF.
// Дозволені навколишні пробіли, початкові нулі та необов'язковий '+'.
// Помилки — у stderr з ненульовим кодом завершення; stdout містить лише ялинку.

#include <cstdint>
#include <iostream>
#include <limits>

namespace {

// Ширина основи 2*n-1 має бути представленною в uint64_t,
// тому допустима межа n — UINT64_MAX/2 + 1.
constexpr std::uint64_t kMaxN = std::numeric_limits<std::uint64_t>::max() / 2 + 1;

constexpr std::size_t kChunkSize = 64;

bool isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

// Друк серії однакових символів фіксованими блоками: O(1) допоміжної пам'яті.
void printRun(std::ostream& out, char fill, std::uint64_t count) {
    char chunk[kChunkSize];
    for (char& c : chunk) {
        c = fill;
    }
    while (count >= kChunkSize) {
        out.write(chunk, static_cast<std::streamsize>(kChunkSize));
        count -= kChunkSize;
    }
    if (count > 0) {
        out.write(chunk, static_cast<std::streamsize>(count));
    }
}

enum class ParseResult { Ok, Empty, Overflow, ExtraInput, MissingDigits, NotPositive, TooLarge };

// Зчитує весь stdin; очікується рівно одне додатне десяткове ціле число до EOF.
ParseResult parseN(std::istream& in, std::uint64_t& value) {
    char c = '\0';
    bool haveChar = false;

    auto next = [&]() -> bool {
        if (haveChar) {
            return true;
        }
        if (!in.get(c)) {
            return false;
        }
        haveChar = true;
        return true;
    };
    auto consume = [&]() { haveChar = false; };

    while (next() && isSpace(c)) {
        consume();
    }
    if (!haveChar) {
        return ParseResult::Empty;
    }

    if (c == '+') {
        consume();
    }

    std::uint64_t acc = 0;
    bool haveDigits = false;
    while (next() && c >= '0' && c <= '9') {
        haveDigits = true;
        const std::uint64_t digit = static_cast<std::uint64_t>(c - '0');
        // Перевірка перед множенням і додаванням: без переповнення.
        if (acc > (std::numeric_limits<std::uint64_t>::max() - digit) / 10) {
            return ParseResult::Overflow;
        }
        acc = acc * 10 + digit;
        consume();
    }
    if (!haveDigits) {
        return ParseResult::MissingDigits;
    }
    while (haveChar && isSpace(c)) {
        consume();
        next();
    }
    if (haveChar) {
        return ParseResult::ExtraInput;
    }
    if (acc == 0) {
        return ParseResult::NotPositive;
    }
    if (acc > kMaxN) {
        return ParseResult::TooLarge;
    }
    value = acc;
    return ParseResult::Ok;
}

const char* parseErrorText(ParseResult r) {
    switch (r) {
        case ParseResult::Empty:
            return "очікувалося одне додатне ціле число, введення порожнє";
        case ParseResult::Overflow:
            return "число не вміщується у 64-бітове ціле (переповнення)";
        case ParseResult::MissingDigits:
            return "очікувалася цифра: дозволене лише одне додатне десяткове ціле";
        case ParseResult::ExtraInput:
            return "зайві символи після числа (має бути рівно одне число до EOF)";
        case ParseResult::NotPositive:
            return "число має бути додатним (>= 1)";
        case ParseResult::TooLarge:
            return "число завелике: ширина основи 2*n-1 не представима";
        case ParseResult::Ok:
            break;
    }
    return "невідома помилка";
}

}  // namespace

int main() {
    std::ios::sync_with_stdio(false);

    std::uint64_t n = 0;
    const ParseResult r = parseN(std::cin, n);
    if (r != ParseResult::Ok) {
        std::cerr << "error: " << parseErrorText(r) << '\n';
        return 1;
    }

    std::ostream& out = std::cout;
    const std::uint64_t width = n + (n - 1);  // 2*n-1 без переповнення для n <= kMaxN
    for (std::uint64_t i = 1; i <= n; ++i) {
        printRun(out, ' ', n - i);
        printRun(out, '*', i + (i - 1));  // 2*i-1
        out.put('\n');
    }
    printRun(out, ' ', n - 1);
    out.put('|');
    out.put('\n');
    out.flush();
    return out.good() ? 0 : 1;
}
